package com.tc.gymtrainer.bot

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.tc.gymtrainer.bot.platform.ChatApi
import com.tc.gymtrainer.bot.platform.Trainer
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.message.textsources.TextSource
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InputStep(val value: String, val message: Long? = null, val day: String)
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RecordedCall(val name: String, val result: String)

/**
 * A dialog between requests: where it started, what the user answered and what each
 * completed step returned. [generation] changes when the dialog restarts from a new entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class DialogDraft(
    val entry: String, val argument: String?, val owner: Long?, val started: String, val generation: Int = 0,
    val inputs: MutableList<InputStep> = mutableListOf(), val calls: MutableList<RecordedCall> = mutableListOf(),
    var expectsText: Boolean = false, var prompt: Long? = null,
)

/** Пользователь прервал сценарий (ввёл «отмена»). */
internal class FlowCancelled : RuntimeException()

/** Control flow of the dialog engine. Flows must let these pass. */
internal sealed class DialogSignal : RuntimeException()
internal class WaitingForInput : DialogSignal()
internal class ChangedDialog : DialogSignal()

/**
 * The state the dialog reached is fully stored on the platform, so its history can be
 * dropped: the dialog continues from [entry] as if it had started there.
 */
internal class RestartDialog(val entry: String, val argument: String) : DialogSignal()

private const val SEND = "sendMessage"

// The same dialog is re-entered on each request. Completed reads, writes and messages
// return their original results. Only the new input can advance past the last wait.
internal class DialogTurn(
    val draft: DialogDraft, private val sender: Long, private val update: Long,
    private val checkpoint: suspend (DialogDraft) -> Unit,
) {
    private var callIndex = 0
    private var inputIndex = 0

    /** A message has been sent since the draft was last stored. */
    var unsavedSend = false
        private set
    var day: LocalDate = LocalDate.parse(draft.started)
        private set

    /** A platform call. A write runs once: its key is the same on every delivery of this update. */
    suspend fun effect(name: String, execute: suspend (UUID) -> String): String {
        val index = callIndex++
        draft.calls.getOrNull(index)?.let {
            if (it.name != name) throw ChangedDialog()
            return it.result
        }
        val key = UUID.nameUUIDFromBytes("$sender:$update:${draft.generation}:$index".toByteArray())
        val result = execute(key)
        draft.calls.add(RecordedCall(name, result))
        return result
    }

    /**
     * A large read that is the same for every trainer, such as the exercise catalog. The
     * draft keeps only a digest of it. The content stays in [memory] and is read from the
     * platform again when this process no longer has it.
     */
    suspend fun shared(name: String, memory: MutableMap<String, String>, load: suspend () -> String): String {
        val index = callIndex++
        draft.calls.getOrNull(index)?.let {
            if (it.name != name) throw ChangedDialog()
            return memory[it.result] ?: load().also { content -> memory[digest(content)] = content }
        }
        val content = load()
        val key = digest(content)
        memory[key] = content
        draft.calls.add(RecordedCall(name, key))
        return content
    }

    private fun digest(content: String) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()), 0, 16)

    /**
     * A Telegram message cannot be repeated safely, so its record is stored before the
     * next message goes out. The last one of a turn is stored when the turn finishes.
     */
    suspend fun send(execute: suspend () -> Long): Long {
        val index = callIndex++
        draft.calls.getOrNull(index)?.let {
            if (it.name != SEND) throw ChangedDialog()
            return it.result.toLong()
        }
        if (unsavedSend) checkpoint(draft)
        val id = execute()
        draft.calls.add(RecordedCall(SEND, id.toString()))
        unsavedSend = true
        return id
    }

    fun input(message: Long?): String {
        val input = draft.inputs.getOrNull(inputIndex++) ?: run {
            draft.expectsText = message == null
            draft.prompt = message
            throw WaitingForInput()
        }
        if (input.message != message) throw ChangedDialog()
        day = LocalDate.parse(input.day)
        if ((message != null && input.value == "cancel") || (message == null && input.value.equals("отмена", true))) throw FlowCancelled()
        return input.value
    }
}

/** What a flow works with: one chat, its platform client and the trainer's calendar. */
internal class Dialog(
    private val telegram: TelegramBot, val chat: Chat, private val turn: DialogTurn,
    val api: ChatApi, val trainer: Trainer?, private val zone: ZoneId,
) {
    /** The trainer's date when the latest answer arrived. A repeated delivery sees the same day. */
    val today: LocalDate get() = turn.day

    fun localDate(value: OffsetDateTime): LocalDate = value.atZoneSameInstant(zone).toLocalDate()
    fun date(value: OffsetDateTime): String = Format.date(localDate(value))

    /** The same moment of the day on another date of the trainer's calendar. */
    fun onDate(value: OffsetDateTime, date: LocalDate): OffsetDateTime =
        value.atZoneSameInstant(zone).with(date).toOffsetDateTime()

    suspend fun send(text: String, replyMarkup: KeyboardMarkup? = null): MessageId =
        MessageId(turn.send { telegram.sendTextMessage(chat, text, replyMarkup = replyMarkup).messageId.long })

    suspend fun send(entities: List<TextSource>, replyMarkup: KeyboardMarkup? = null): MessageId =
        MessageId(turn.send { telegram.sendTextMessage(chat, entities = entities, replyMarkup = replyMarkup).messageId.long })

    /** Ждёт текстовое сообщение. Слово «отмена» прерывает сценарий ([FlowCancelled]). */
    fun awaitText(): String = turn.input(null)

    /** Ждёт нажатие inline-кнопки под сообщением [prompt] и возвращает её callback data. */
    fun awaitCallback(prompt: MessageId): String = turn.input(prompt.long)
}
