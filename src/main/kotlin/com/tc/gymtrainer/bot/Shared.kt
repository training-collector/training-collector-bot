package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.ServiceException
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.utils.row

/** Карточка может занимать несколько сообщений. Кнопки относятся к последней части. */
internal suspend fun Dialog.sendCard(text: String, replyMarkup: KeyboardMarkup? = null): MessageId {
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
        if (chunk.isNotBlank()) send(chunk)
        start = end
    }
    return send(content.substring(start), replyMarkup = replyMarkup)
}

/**
 * Оборачивает диалоговый сценарий: «отмена» и доменные ошибки показываются
 * пользователю по-русски. Непредвиденная ошибка уходит наверх: обновление будет
 * доставлено повторно, а после нескольких неудач диалог сбрасывается.
 */
internal suspend fun Dialog.runFlow(menu: Boolean, block: suspend () -> Unit) {
    try {
        block()
    } catch (_: FlowCancelled) {
        send("Отменено.", replyMarkup = trainingKeyboard)
    } catch (e: ServiceException) {
        send("⚠️ ${e.message}", replyMarkup = if (menu) trainingKeyboard else null)
    }
}

/**
 * Показывает список подопечных тренера и ждёт выбора. Возвращает выбранного
 * клиента, либо null, если список пуст.
 */
internal suspend fun Dialog.selectClient(prompt: String, includeArchived: Boolean = false): Client? {
    val list = api.clients(includeArchived)
    if (list.isEmpty()) {
        showClients()
        return null
    }
    send(prompt, replyMarkup = trainingKeyboard)
    val selected = chooseItem("Подопечные", list.map { Format.clientLine(it) to it.id.toString() })
    return api.client(selected.toLong())
}

internal suspend fun Dialog.sendListPage(
    title: String, items: List<Pair<String, String>>, first: List<Pair<String, String>>,
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
    return sendCard(caption, replyMarkup = keyboard)
}

internal suspend fun Dialog.chooseItem(
    title: String, items: List<Pair<String, String>>,
    footer: Pair<String, String> = TrainingAction.CANCEL.label to "cancel",
): String {
    var page = 0
    while (true) {
        val prompt = sendListPage(title, items, emptyList(), page, footer) { "page:$it" }
        val data = awaitCallback(prompt)
        if (data.startsWith("page:")) page = data.substringAfter(':').toIntOrNull() ?: page
        else if (data == footer.second || items.any { it.second == data }) return data
    }
}

internal suspend fun Dialog.chooseReply(
    prompt: String, vararg actions: String, exit: TrainingAction = TrainingAction.CANCEL,
    keyboard: KeyboardMarkup = dialogKeyboard(*actions, exit = exit),
): String {
    sendCard(prompt, replyMarkup = keyboard)
    while (true) {
        val action = awaitText()
        if (action in actions) return action
        send("Выберите действие на клавиатуре.")
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
