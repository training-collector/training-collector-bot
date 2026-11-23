package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.Trainer
import com.tc.gymtrainer.bot.platform.ClientService
import com.tc.gymtrainer.bot.platform.ServiceException
import com.tc.gymtrainer.bot.platform.TrainerService
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.message.textsources.TextSource
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import kotlinx.coroutines.currentCoroutineContext
import dev.inmo.tgbotapi.utils.row
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CancellationException

private val flowLog = LoggerFactory.getLogger("com.tc.gymtrainer.bot.Flow")

private suspend fun sentMessage(send: suspend () -> MessageId): MessageId {
    val turn = currentCoroutineContext()[DialogTurn] ?: return send()
    return MessageId(turn.effect("sendMessage") { send().long.toString() }.toLong())
}

internal suspend fun BehaviourContext.sendDialogText(chat: Chat, text: String, replyMarkup: KeyboardMarkup? = null) =
    sentMessage { sendTextMessage(chat, text, replyMarkup = replyMarkup).messageId }

internal suspend fun BehaviourContext.sendDialogText(chat: Chat, entities: List<TextSource>, replyMarkup: KeyboardMarkup? = null) =
    sentMessage { sendTextMessage(chat, entities = entities, replyMarkup = replyMarkup).messageId }

/** Карточка может занимать несколько сообщений. Кнопки относятся к последней части. */
internal suspend fun BehaviourContext.sendCard(chat: Chat, text: String, replyMarkup: KeyboardMarkup? = null): MessageId {
    val limit = 4096
    val content = text.trim()
    var start = 0
    while (content.length - start > limit) {
        val boundary = start + limit
        val newline = content.lastIndexOf('\n', boundary - 1)
        val end = when {
            newline >= start -> newline + 1
            content[boundary - 1].isHighSurrogate() -> boundary - 1
            else -> boundary
        }
        val chunk = content.substring(start, end)
        if (chunk.isNotBlank()) sendDialogText(chat, chunk)
        start = end
    }
    return sendDialogText(chat, content.substring(start), replyMarkup = replyMarkup)
}

/**
 * Оборачивает диалоговый сценарий: «отмена» и доменные ошибки показываются
 * пользователю по-русски, непредвиденные — логируются и не роняют бота.
 */
internal suspend fun BehaviourContext.runFlow(chat: Chat, dialog: Boolean = false, block: suspend () -> Unit) {
    try {
        block()
    } catch (_: AccessRevoked) {
        sendDialogText(chat, "Доступ к боту закрыт.", replyMarkup = ReplyKeyboardRemove())
    } catch (_: FlowCancelled) {
        sendDialogText(chat, "Отменено.", replyMarkup = trainingKeyboard)
    } catch (e: ServiceException) {
        sendDialogText(chat, "⚠️ ${e.message}", replyMarkup = if (dialog) trainingKeyboard else null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (kotlinx.coroutines.currentCoroutineContext()[DialogTurn] != null) throw e
        flowLog.error("Ошибка в диалоге бота", e)
        sendDialogText(chat, "Произошла ошибка. Попробуйте ещё раз.", replyMarkup = if (dialog) trainingKeyboard else null)
    }
}

/** Доступ к данным тренера открыт только в личном чате после одобрения регистрации. */
internal suspend fun BehaviourContext.requireTrainer(
    chat: Chat, sender: Long, trainers: TrainerService,
): Trainer? {
    val trainer = trainers.findByTelegramUserId(sender)
    if (trainer == null) {
        sendDialogText(chat, "Активация не подтверждена. Войдите на сайт, откройте свой профиль справа вверху и нажмите «Подключить бота для тренировок».",
            replyMarkup = ReplyKeyboardRemove())
        return null
    }
    return trainer
}

/**
 * Показывает список подопечных тренера и ждёт выбора. Возвращает выбранного
 * клиента, либо null (список пуст, либо пользователь нажал «Отмена»).
 */
internal suspend fun BehaviourContext.selectClient(
    chat: Chat,
    userId: Long,
    trainer: Trainer,
    clients: ClientService,
    prompt: String = "Выберите подопечного:",
    includeArchived: Boolean = false,
): Client? {
    val list = clients.list(trainer.telegramUserId, includeArchived)
    if (list.isEmpty()) {
        showClients(chat, trainer, clients)
        return null
    }
    sendDialogText(chat, prompt, replyMarkup = trainingKeyboard)
    val selected = chooseItem(chat, userId, "Подопечные", list.map { Format.clientLine(it) to it.id.toString() })
    return clients.getOwned(trainer.telegramUserId, selected.toLong())
}

internal suspend fun BehaviourContext.sendListPage(
    chat: Chat, title: String, items: List<Pair<String, String>>, first: List<Pair<String, String>>,
    requestedPage: Int, footer: Pair<String, String>? = null, pageData: (Int) -> String,
): MessageId {
    val pages = items.chunked(25).ifEmpty { listOf(emptyList()) }
    val page = requestedPage.coerceIn(0, pages.lastIndex)
    val keyboard = inlineKeyboard {
        first.forEach { (label, data) -> row { dataButton(label, data) } }
        pages[page].forEach { (label, data) -> row { dataButton(label, data) } }
        if (pages.size > 1) row {
            if (page > 0) dataButton("$BACK_ICON Назад", pageData(page - 1))
            if (page < pages.lastIndex) dataButton("Дальше →", pageData(page + 1))
        }
        if (footer != null) row { dataButton(footer.first, footer.second) }
    }
    val caption = if (pages.size == 1) title else "$title · ${page + 1} из ${pages.size}"
    return sendCard(chat, caption, replyMarkup = keyboard)
}

internal suspend fun BehaviourContext.chooseItem(
    chat: Chat, userId: Long, title: String, items: List<Pair<String, String>>,
    footer: Pair<String, String> = TrainingAction.CANCEL.label to "cancel",
): String {
    var page = 0
    while (true) {
        val prompt = sendListPage(chat, title, items, emptyList(), page, footer) { "page:$it" }
        val data = awaitCallbackData(userId, prompt)
        if (data.startsWith("page:")) page = data.substringAfter(':').toIntOrNull() ?: page
        else if (data == footer.second || items.any { it.second == data }) return data
    }
}

internal suspend fun BehaviourContext.chooseReply(
    chat: Chat, userId: Long, prompt: String, vararg actions: String, exit: TrainingAction = TrainingAction.CANCEL,
    keyboard: KeyboardMarkup = dialogKeyboard(*actions, exit = exit),
): String {
    sendCard(chat, prompt, replyMarkup = keyboard)
    while (true) {
        val action = awaitText(userId)
        if (action in actions) return action
        sendDialogText(chat, "Выберите действие на клавиатуре.")
    }
}

/** Клавиатура «Да / Нет» с заданными callback data. */
internal fun yesNoKeyboard(yesData: String, noData: String, yesLabel: String = "Да", noLabel: String = "Нет") =
    inlineKeyboard {
        row {
            dataButton(yesLabel, yesData)
            dataButton(noLabel, noData)
        }
        row { dataButton("Отмена", "cancel") }
    }
