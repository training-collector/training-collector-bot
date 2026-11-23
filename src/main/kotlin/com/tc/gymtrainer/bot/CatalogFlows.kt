package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.ExerciseCatalogItem
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.utils.row

internal suspend fun Dialog.showExerciseGroups(page: Int = 0) {
    val groups = api.groups()
    if (groups.isEmpty()) {
        sendCard("Категорий упражнений пока нет.")
        return
    }
    sendListPage("Упражнения · Выберите группу мышц", groups.map { it.name to "ex:group:${it.id}" },
        emptyList(), page) { "ex:page:$it" }
}

internal suspend fun Dialog.showGroupExercises(groupId: Long, page: Int) {
    val group = api.groups().find { it.id == groupId }
    val items = api.exercises(groupId)
    val title = when {
        group == null -> "Категория не найдена."
        items.isEmpty() -> "${group.name} · В категории пока нет упражнений."
        else -> "Упражнения · ${group.name}"
    }
    sendListPage(title, items.map { it.name to "ex:view:${it.id}" }, emptyList(), page,
        BACK_TO_CATEGORIES to "ex:page:0") { "ex:group:$groupId:$it" }
}

internal suspend fun Dialog.showCatalogExercise(item: ExerciseCatalogItem) {
    val text = "Название: ${item.name}\n\nОписание: ${item.description.orEmpty().ifBlank { "не указано" }}"
    sendCard(text, replyMarkup = inlineKeyboard {
        row { dataButton("$BACK_ICON К упражнениям", "ex:group:${item.muscleGroupId}") }
    })
}
