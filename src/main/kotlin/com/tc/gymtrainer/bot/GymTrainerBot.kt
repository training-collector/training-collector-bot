package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.ValidationException
import com.tc.gymtrainer.bot.platform.WorkoutStage
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.message.abstracts.PossiblyMarkedUp
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery

/*
 * Точки входа диалогов тренерского бота: /start, действия главного меню и кнопки под
 * сообщениями. Бизнес-логики здесь нет: только диалоги, разбор ввода и форматирование.
 */

private const val NOT_CONNECTED =
    "Активация не подтверждена. Войдите на сайт, откройте свой профиль справа вверху и нажмите «Подключить бота для тренировок»."

internal suspend fun Dialog.welcome(token: String?) {
    val connected = if (token != null) api.connect(token) else trainer
    if (connected == null) {
        send(NOT_CONNECTED, replyMarkup = ReplyKeyboardRemove())
        return
    }
    val greeting = if (token != null) "Бот для тренировок подключён к профилю ${connected.username ?: connected.fullName}."
        else "С возвращением, ${connected.fullName}! Ваш логин: ${connected.username ?: connected.fullName}."
    send("$greeting\n\nВыберите действие.", replyMarkup = trainingKeyboard)
}

internal suspend fun Dialog.handleAction(action: TrainingAction) {
    when (action) {
        TrainingAction.ADD_CLIENT -> addClient()
        TrainingAction.EXERCISES -> showExerciseGroups()
        TrainingAction.CLIENTS -> showClients()
        TrainingAction.WORKOUT -> recordWorkout(selectClient("Для кого записываем тренировку?") ?: return)
        TrainingAction.HISTORY -> showWorkoutHistory(selectClient("Чью историю показать?", includeArchived = true) ?: return)
        TrainingAction.MEASURE -> showMeasurementHistory(selectClient("Чьи замеры?", includeArchived = true) ?: return)
        TrainingAction.HELP -> send(trainingHelp, replyMarkup = trainingKeyboard)
        TrainingAction.MENU -> send("Выберите действие.", replyMarkup = trainingKeyboard)
        TrainingAction.CANCEL -> send("Отменено.", replyMarkup = trainingKeyboard)
    }
}

/** A button under a message. [data] has already matched [menuCallback]. */
internal suspend fun Dialog.handleMenu(data: String) {
    val parts = data.split(':')
    val action = parts.take(2).joinToString(":")
    fun id(index: Int = 2) = parts.getOrNull(index)?.toLongOrNull() ?: throw ValidationException("Некорректная запись.")
    fun page(index: Int) = parts.getOrNull(index)?.toIntOrNull() ?: 0
    when (action) {
        "cl:page" -> showClients(page(2))
        "cl:add" -> addClient()
        "cl:view" -> showClientCard(api.client(id()))
        "ex:page" -> showExerciseGroups(page(2))
        "ex:group" -> showGroupExercises(id(), page(3))
        "ex:view" -> showCatalogExercise(api.exercise(id()))
        "w:add" -> recordWorkout(api.client(id()))
        "w:hist" -> {
            val client = api.client(id())
            if (parts.size == 3) showWorkoutMenu(client) else showWorkoutHistory(client, page(3))
        }
        "w:view" -> showCompletedWorkout(id())
        "w:cancel" -> confirmWorkoutCancellation(api.client(id()), id(3))
        "w:discard" -> {
            val client = api.client(id())
            val removed = api.cancelActiveWorkout(client.id, id(3))
            showWorkoutMenu(client, notice = if (removed) "Тренировка отменена." else WORKOUT_NO_LONGER_ACTIVE)
        }
        "w:resume", "w:finish" -> {
            val session = api.workout(id()).session
            val client = api.client(session.clientId)
            if (action == "w:finish" && session.stage != WorkoutStage.COMPLETED) finishWorkout(client, session.id, parts.getOrNull(3))
            else recordWorkout(client, session.id)
        }
        "m:add", "m:hist" -> {
            // Older messages carry only the action and need an explicit client choice.
            val client = if (parts.size == 2) selectClient("Выберите подопечного для замеров.", includeArchived = true) ?: return
                else api.client(id())
            if (action == "m:add") addMeasurement(client) else showMeasurementHistory(client, page(3))
        }
        "m:view" -> showMeasurement(id())
        "m:close" -> send("Главное меню", replyMarkup = trainingKeyboard)
    }
}

/** Continues an active workout after the dialog restarted. [argument] is "clientId:sessionId". */
internal suspend fun Dialog.resumeWorkout(argument: String) {
    val (client, session) = argument.split(':').map { it.toLongOrNull() ?: throw ChangedDialog() }.also { if (it.size != 2) throw ChangedDialog() }
    recordWorkout(api.client(client), session)
}

// Telegram keeps the original keyboard on messages sent before the workout menu changed.
internal fun MessageDataCallbackQuery.menuDestination(): String {
    val keyboard = (message as? PossiblyMarkedUp)?.replyMarkup?.keyboard ?: return data
    val buttons = keyboard.flatten().filterIsInstance<CallbackDataInlineKeyboardButton>()
    val oldClose = buttons.firstOrNull { it.text == CLOSE_WORKOUT } ?: return data
    return when {
        oldClose.callbackData == data && data.startsWith("cl:view:") -> "w:hist:" + data.substringAfterLast(':')
        oldClose.callbackData.startsWith("w:finish:") && (data == oldClose.callbackData || data.startsWith("w:resume:")) ->
            buttons.firstOrNull { it.callbackData.startsWith("w:hist:") }?.callbackData ?: data
        else -> data
    }
}
