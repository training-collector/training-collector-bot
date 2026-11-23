package com.tc.gymtrainer.bot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tc.gymtrainer.bot.platform.Lease
import com.tc.gymtrainer.bot.platform.Trainer
import com.tc.gymtrainer.bot.platform.TrainingPlatform
import com.tc.gymtrainer.bot.platform.UpdateApi
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.types.update.CallbackQueryUpdate
import dev.inmo.tgbotapi.types.update.MessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private data class Input(val chat: Chat, val text: String, val prompt: Long? = null, val query: MessageDataCallbackQuery? = null)

class BotSettings(
    val username: String,
    /** Used for dates until the trainer's own zone is known from their browser. */
    val defaultZone: ZoneId = ZoneOffset.UTC,
    /** After this many failed deliveries of one update the dialog is reset. */
    val maxUpdateAttempts: Int = 3,
)

/** Where an update failed. The message carries identifiers only, never what the user wrote. */
class UpdateFailure(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Handles one Telegram update of the training bot. The polling loop calls it for each
 * update in order; tests call it directly.
 */
class UpdateHandler(private val platform: TrainingPlatform, private val telegram: TelegramBot, private val settings: BotSettings) {
    constructor(platform: TrainingPlatform, telegram: TelegramBot, username: String) : this(platform, telegram, BotSettings(username))

    private val json = jacksonObjectMapper()
    private val log = LoggerFactory.getLogger(UpdateHandler::class.java)

    suspend fun process(update: Update) {
        val input = when (update) {
            is MessageUpdate -> {
                val message = update.data as? CommonMessage<*> ?: return
                val sender = (message as? FromUserMessage)?.from?.id?.chatId?.long
                if (message.chat !is PrivateChat || sender != message.chat.id.chatId.long) return
                Input(message.chat, (message.content as? TextContent)?.text ?: return)
            }
            is CallbackQueryUpdate -> {
                val query = update.data as? MessageDataCallbackQuery ?: return
                val chat = query.message.chat
                if (chat !is PrivateChat || chat.id.chatId.long != query.from.id.chatId.long) return
                Input(chat, query.menuDestination(), query.message.messageId.long, query)
            }
            else -> return
        }
        val sender = input.chat.id.chatId.long
        val updates = platform.updates(sender, update.updateId.long)
        val lease = updates.begin()
        if (lease.status == "done") return
        check(lease.status == "claimed") { "Update is already being processed" }
        try {
            handle(input, sender, update.updateId.long, lease, updates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Free the chat at once, so the repeated delivery does not wait for the lease to expire.
            runCatching { updates.release(lease) }
            val step = (e as? UpdateFailure)?.message?.let { " $it" }.orEmpty()
            throw UpdateFailure("update=${update.updateId.long} chat=$sender attempt=${lease.attempt}$step", (e as? UpdateFailure)?.cause ?: e)
        }
    }

    private suspend fun handle(input: Input, sender: Long, update: Long, lease: Lease, updates: UpdateApi) {
        // An expired callback can no longer show a spinner. It must not block its action.
        input.query?.let { query ->
            try { telegram.answerCallbackQuery(query) }
            catch (e: RequestException) {
                val detail = e.response.description.orEmpty()
                if (e.response.errorCode != 400 || (!detail.contains("query is too old", true) && !detail.contains("query ID is invalid", true))) throw e
            }
        }
        if (lease.attempt > settings.maxUpdateAttempts) {
            // The same update keeps failing. Without this it would be redelivered forever and block the bot.
            log.error("Update {} of chat {} failed {} times; its dialog is reset", update, sender, lease.attempt - 1)
            runCatching { telegram.sendTextMessage(input.chat, "Произошла ошибка. Попробуйте ещё раз.", replyMarkup = trainingKeyboard) }
            updates.finish(lease, null)
            return
        }
        val trainer = lease.trainer
        val zone = trainer?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: settings.defaultZone
        val today = LocalDate.now(zone).toString()
        val old = readDraft(lease.draft)
        val command = input.text.substringBefore(' ')
        val start = input.prompt == null && (command == "/start" || command.equals("/start@${settings.username}", true))
        val action = if (input.prompt == null) TrainingAction.from(input.text, settings.username) else null
        val menu = input.prompt != null && menuCallback.matches(input.text)
        var draft = readDraft(lease.checkpoint) ?: when {
            start -> DialogDraft("start", input.text.substringAfter(' ', "").trim().ifEmpty { null }, trainer?.id, today)
            action != null -> DialogDraft("action", action.name, trainer?.id, today)
            menu -> DialogDraft("menu", input.text, trainer?.id, today)
            old != null && old.owner == trainer?.id && old.expectsText == (input.prompt == null) && old.prompt == input.prompt ->
                old.apply { inputs.add(InputStep(input.text.trim(), input.prompt, today)) }
            else -> {
                if (old != null && trainer == null) telegram.sendTextMessage(input.chat, "Доступ к боту закрыт.", replyMarkup = ReplyKeyboardRemove())
                updates.finish(lease, old.takeIf { trainer != null && it?.owner == trainer.id }?.let(json::writeValueAsString))
                return
            }
        }
        var next: DialogDraft?
        while (true) {
            val turn = DialogTurn(draft, sender, update) { updates.checkpoint(lease, json.writeValueAsString(it)) }
            val dialog = Dialog(telegram, input.chat, turn, platform.chat(sender, turn), trainer, zone)
            try {
                dialog.runFlow(menu = draft.entry != "start") { dialog.enter(draft, trainer) }
                next = null
                break
            } catch (_: WaitingForInput) {
                next = draft
                break
            } catch (_: ChangedDialog) {
                telegram.sendTextMessage(input.chat, "Меню обновилось. Выберите действие.", replyMarkup = trainingKeyboard)
                next = null
                break
            } catch (restart: RestartDialog) {
                draft = DialogDraft(restart.entry, restart.argument, draft.owner, dialog.today.toString(), draft.generation + 1)
                // Messages already sent in this turn must not go out again if the update is redelivered.
                if (turn.unsavedSend) updates.checkpoint(lease, json.writeValueAsString(draft))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Which dialog and how far it got, without quoting any of its content.
                throw UpdateFailure("dialog=${draft.entry} step=${draft.inputs.size}", e)
            }
        }
        updates.finish(lease, next?.let(json::writeValueAsString))
    }

    private suspend fun Dialog.enter(draft: DialogDraft, trainer: Trainer?) {
        when {
            draft.entry == "start" -> welcome(draft.argument)
            trainer == null || trainer.id != draft.owner ->
                send("Активация не подтверждена. Войдите на сайт и подключите бота в профиле.", replyMarkup = ReplyKeyboardRemove())
            draft.entry == "action" -> handleAction(TrainingAction.entries.find { it.name == draft.argument } ?: throw ChangedDialog())
            draft.entry == "menu" -> handleMenu(draft.argument ?: throw ChangedDialog())
            draft.entry == "workout" -> resumeWorkout(draft.argument ?: throw ChangedDialog())
            // A draft written by another version of the bot.
            else -> throw ChangedDialog()
        }
    }

    /** A draft that cannot be read is dropped: the user starts the step again instead of the bot failing forever. */
    private fun readDraft(stored: String?): DialogDraft? = stored?.let {
        try {
            json.readValue<DialogDraft>(it)
        } catch (e: Exception) {
            log.warn("A stored dialog draft is unreadable ({}) and is discarded", e.javaClass.simpleName)
            null
        }
    }
}
