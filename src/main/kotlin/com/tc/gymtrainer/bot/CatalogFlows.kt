package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.ExerciseCatalogItem
import com.tc.gymtrainer.bot.platform.CatalogService
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.utils.row

internal suspend fun BehaviourContext.showExerciseGroups(chat: Chat, senderId: Long, catalog: CatalogService, page: Int = 0) {
    val groups = catalog.groups(senderId)
    if (groups.isEmpty()) {
        sendCard(chat, "Категорий упражнений пока нет.")
        return
    }
    sendListPage(chat, "Упражнения · Выберите группу мышц", groups.map { it.name to "ex:group:${it.id}" },
        emptyList(), page) { "ex:page:$it" }
}

internal suspend fun BehaviourContext.showGroupExercises(chat: Chat, senderId: Long, catalog: CatalogService, groupId: Long, page: Int) {
    val group = catalog.groups(senderId).find { it.id == groupId }
    val items = catalog.exercisesFor(senderId, groupId)
    val title = when {
        group == null -> "Категория не найдена."
        items.isEmpty() -> "${group.name} · В категории пока нет упражнений."
        else -> "Упражнения · ${group.name}"
    }
    sendListPage(chat, title, items.map { it.name to "ex:view:${it.id}" }, emptyList(), page,
        BACK_TO_CATEGORIES to "ex:page:0") { "ex:group:$groupId:$it" }
}

private fun exerciseCardText(name: String, description: String?): String =
    "Название: $name\n\nОписание: ${description.orEmpty().ifBlank { "не указано" }}"

internal suspend fun BehaviourContext.showCatalogExercise(chat: Chat, item: ExerciseCatalogItem) {
    sendCard(chat, exerciseCardText(item.name, item.description), replyMarkup = inlineKeyboard {
        row { dataButton("$BACK_ICON К упражнениям", "ex:group:${item.muscleGroupId}") }
    })
}
