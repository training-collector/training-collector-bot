package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.ExerciseCatalogItem
import com.tc.gymtrainer.bot.platform.CatalogService
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.utils.row

internal suspend fun BehaviourContext.showExercises(chat: Chat, senderId: Long, catalog: CatalogService, page: Int = 0) {
    val items = catalog.exercisesFor(senderId)
    if (items.isEmpty()) {
        sendCard(chat, "Список упражнений пока пуст.")
        return
    }
    sendListPage(chat, "Упражнения", items.map { it.name to "ex:view:${it.id}" },
        emptyList(), page) { "ex:page:$it" }
}

private fun exerciseCardText(name: String, description: String?): String =
    "Название: $name\n\nОписание: ${description.orEmpty().ifBlank { "не указано" }}"

internal suspend fun BehaviourContext.showCatalogExercise(chat: Chat, item: ExerciseCatalogItem) {
    sendCard(chat, exerciseCardText(item.name, item.description), replyMarkup = inlineKeyboard {
        row { dataButton("К упражнениям", "ex:page:0") }
    })
}
