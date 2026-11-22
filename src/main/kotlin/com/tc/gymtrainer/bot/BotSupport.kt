package com.tc.gymtrainer.bot

import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.types.MessageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Пользователь прервал сценарий (ввёл «отмена»). */
internal class FlowCancelled : RuntimeException()

internal class AccessRevoked : RuntimeException()

// A reply can arrive after an administrator has disabled or deleted the trainer.
internal class DialogAccess(val active: suspend () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DialogAccess>
}

private suspend fun checkDialogAccess() {
    if (currentCoroutineContext()[DialogAccess]?.active?.invoke() == false) throw AccessRevoked()
}

/** Telegram user id из приватного чата (в личке id чата совпадает с id пользователя). */
internal val CommonMessage<*>.userId: Long
    get() = chat.id.chatId.long

internal val CommonMessage<*>.privateSenderId: Long?
    get() = (this as? FromUserMessage)?.from?.id?.chatId?.long?.takeIf { chat is PrivateChat && it == userId }

internal val DataCallbackQuery.userId: Long
    get() = from.id.chatId.long

/**
 * Ждёт текстовое сообщение от конкретного пользователя. Слова «отмена»/«/cancel»
 * прерывают сценарий ([FlowCancelled]).
 */
internal suspend fun BehaviourContext.awaitText(userId: Long): String {
    val msg = waitTextMessage().first {
        val text = it.content.text
        it.privateSenderId == userId && TrainingAction.from(text) == null && text.substringBefore(' ').substringBefore('@') != "/start"
    }
    checkDialogAccess()
    val text = msg.content.text.trim()
    if (text.equals("отмена", ignoreCase = true)) throw FlowCancelled()
    return text
}

/**
 * Ждёт нажатие inline-кнопки от пользователя, подтверждает запрос (гасит «часики»)
 * и возвращает callback data.
 */
internal suspend fun BehaviourContext.awaitCallbackData(userId: Long, prompt: MessageId): String {
    val query = waitDataCallbackQuery().first {
        it is MessageDataCallbackQuery && it.userId == userId && it.message.chat.id.chatId.long == userId && it.message.messageId == prompt
    }
    answerCallbackQuery(query)
    checkDialogAccess()
    if (query.data == "cancel") throw FlowCancelled()
    return query.data
}

internal fun CommonMessage<TextContent>.commandArgument(): String? =
    content.text.substringAfter(' ', "").trim().ifBlank { null }
