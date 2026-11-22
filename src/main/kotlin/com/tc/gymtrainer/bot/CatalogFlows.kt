package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.ExerciseCatalogItem
import com.tc.gymtrainer.bot.platform.CatalogService
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.utils.row

internal suspend fun BehaviourContext.showExercises(chat: Chat, senderId: Long, catalog: CatalogService, page: Int = 0) {
    val items = catalog.exercisesFor(senderId)
    val title = if (items.isEmpty()) "Список упражнений пока пуст." else "Мои упражнения"
    sendListPage(chat, title, items.map { it.name to "ex:view:${it.id}" },
        listOf(addExerciseButtonLabel to "ex:add"), page) { "ex:page:$it" }
}

private fun exerciseCardText(name: String, description: String?): String =
    "Название: $name\n\nОписание: ${description.orEmpty().ifBlank { "не указано" }}"

internal suspend fun BehaviourContext.showCatalogExercise(chat: Chat, item: ExerciseCatalogItem) {
    sendCard(chat, exerciseCardText(item.name, item.description), replyMarkup = inlineKeyboard {
        row { dataButton("К упражнениям", "ex:page:0") }
    })
}

internal suspend fun BehaviourContext.createCatalogExercise(
    chat: Chat, userId: Long, senderId: Long, catalog: CatalogService,
): ExerciseCatalogItem {
    sendTextMessage(chat, "Введите название упражнения.", replyMarkup = dialogKeyboard())
    var name = awaitText(userId)
    while (name.length !in 1..255) {
        sendTextMessage(chat, "Введите название длиной от 1 до 255 символов.")
        name = awaitText(userId)
    }
    val addDescription = "Добавить описание"
    val options = replyKeyboard(resizeKeyboard = true) {
        row { simpleButton(SAVE) }
        row { simpleButton(TrainingAction.CANCEL.label) }
        row { simpleButton(addDescription) }
    }
    val action = chooseReply(chat, userId, "Сохранить «$name» в вашем списке упражнений?", SAVE, addDescription, keyboard = options)
    if (action == SAVE) return catalog.addExercise(senderId, name = name)

    sendTextMessage(chat, "Введите описание упражнения «$name».", replyMarkup = dialogKeyboard(SKIP))
    val description = awaitText(userId).takeUnless { it.isBlank() || it == SKIP }
    chooseReply(chat, userId, "${exerciseCardText(name, description)}\n\nСохранить упражнение?", SAVE)
    return catalog.addExercise(senderId, name = name, description = description)
}
