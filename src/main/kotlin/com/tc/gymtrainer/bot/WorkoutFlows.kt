package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.ExerciseDraft
import com.tc.gymtrainer.bot.platform.SetDraft
import com.tc.gymtrainer.bot.platform.WorkoutDetail
import com.tc.gymtrainer.bot.platform.WorkoutStage
import com.tc.gymtrainer.bot.platform.CatalogService
import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.ExerciseCatalogItem
import com.tc.gymtrainer.bot.platform.ValidationException
import com.tc.gymtrainer.bot.platform.WorkoutService
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.message.textsources.boldTextSource
import dev.inmo.tgbotapi.types.message.textsources.regularTextSource
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.utils.row
import java.time.LocalDate

/**
 * Запись подходов, история и редактирование тренировок подопечного.
 */
internal suspend fun BehaviourContext.recordWorkout(
    chat: Chat,
    userId: Long,
    senderId: Long,
    client: Client,
    catalog: CatalogService,
    workouts: WorkoutService,
    existingSessionId: Long? = null,
) {
    val sessionId = existingSessionId ?: workouts.startOrResume(senderId, client.id).id
    try {
        while (true) {
            val detail = workouts.getDetail(senderId, sessionId)
            when (detail.session.stage) {
                WorkoutStage.CHOOSE_TEMPLATE -> workouts.skipLegacyTemplate(senderId, sessionId)
                WorkoutStage.CHOOSE_EXERCISE -> {
                    val item = selectCatalogExercise(chat, userId, senderId, catalog, "Идёт тренировка · ${client.fullName}\n")
                    workouts.selectExercise(senderId, sessionId, item.id)
                }
                WorkoutStage.ENTER_SETS -> {
                    val (exercise, sets) = detail.exercises.last()
                    collectSets(chat, userId, exercise.exerciseName,
                        initial = sets.map { SetDraft(it.weightKg, it.reps, it.restSec, it.rpe, it.durationSec) },
                        exit = TrainingAction.MENU,
                        onAppend = { workouts.appendActiveSets(senderId, sessionId, exercise.id, it) },
                        onUndo = { workouts.undoActiveSet(senderId, sessionId, exercise.id) })
                    workouts.finishExercise(senderId, sessionId, exercise.id)
                }
                WorkoutStage.REVIEW -> {
                    val summary = detail.exercises.joinToString("\n") { "${it.exercise.exerciseName} · подходов: ${it.sets.size}" }
                    val more = "Ещё упражнение"
                    val action = chooseReply(chat, userId, "$summary\nУпражнений в тренировке: ${detail.exercises.size}", more, FINISH_WORKOUT, exit = TrainingAction.MENU)
                    if (action == FINISH_WORKOUT) {
                        finishWorkout(chat, senderId, client, workouts, sessionId)
                        return
                    }
                    workouts.chooseAnotherExercise(senderId, sessionId)
                }
                WorkoutStage.COMPLETED -> {
                    sendCard(chat, "Тренировка уже завершена.\n${client.fullName}",
                        replyMarkup = clientActionsKeyboard(client.id))
                    return
                }
            }
        }
    } catch (_: FlowCancelled) {
        sendTextMessage(chat, "Тренировка сохранена. Продолжить её можно в разделе «Тренировки» подопечного.", replyMarkup = trainingKeyboard)
    }
}

internal suspend fun BehaviourContext.finishWorkout(
    chat: Chat, senderId: Long, client: Client, workouts: WorkoutService, sessionId: Long,
) {
    val detail = workouts.finishWorkout(senderId, sessionId)
    sendTextMessage(chat, "Тренировка завершена.", replyMarkup = trainingKeyboard)
    sendCard(chat, "${client.fullName}\n\n${Format.workoutDetail(detail)}",
        replyMarkup = clientActionsKeyboard(client.id))
}

internal suspend fun BehaviourContext.showWorkoutHistory(
    chat: Chat, senderId: Long, client: Client, workouts: WorkoutService, page: Int = 0,
) {
    val sessions = workouts.history(senderId, client.id)
    val active = workouts.activeForClient(senderId, client.id)
    val actions = if (active == null) listOf(TrainingAction.WORKOUT.label to "w:add:${client.id}") else listOf(
        "Продолжить тренировку" to "w:resume:${active.id}",
        FINISH_WORKOUT to "w:finish:${active.id}",
    )
    val title = "Тренировки · ${client.fullName}" + if (sessions.isEmpty()) "\n\nЗавершённых тренировок пока нет." else ""
    sendListPage(chat, title, sessions.map { Format.sessionLine(it) to "w:view:${it.id}" }, actions, page,
        "К подопечному" to "cl:view:${client.id}") { "w:hist:${client.id}:$it" }
}

internal suspend fun BehaviourContext.showCompletedWorkout(
    chat: Chat, userId: Long, senderId: Long, sessionId: Long, catalog: CatalogService, workouts: WorkoutService,
) {
    val detail = workouts.getDetail(senderId, sessionId)
    if (detail.session.stage != WorkoutStage.COMPLETED) throw ValidationException("Тренировка ещё идёт. Откройте её в разделе «Тренировки».")
    sendCard(chat, Format.workoutDetail(detail))
    editWorkout(chat, userId, senderId, sessionId, catalog, workouts)
}

private suspend fun BehaviourContext.selectCatalogExercise(
    chat: Chat, userId: Long, senderId: Long, catalog: CatalogService, heading: String = "",
): ExerciseCatalogItem {
    val items = catalog.exercisesFor(senderId)
    if (items.isEmpty()) throw ValidationException("Администратор ещё не опубликовал упражнения.")
    val selection = chooseItem(chat, userId, heading + "Выберите упражнение", items.map { it.name to it.id.toString() })
    return items.first { it.id.toString() == selection }
}

private suspend fun BehaviourContext.collectExercise(
    chat: Chat, userId: Long, senderId: Long, catalog: CatalogService,
): ExerciseDraft {
    val item = selectCatalogExercise(chat, userId, senderId, catalog)
    return ExerciseDraft(null, item.id, item.name, sets = collectSets(chat, userId, item.name))
}

private const val UNDO_SET = "Убрать последний подход"

private suspend fun BehaviourContext.collectSets(
    chat: Chat, userId: Long, name: String, initial: List<SetDraft> = emptyList(),
    exit: TrainingAction = TrainingAction.CANCEL,
    onAppend: suspend (List<SetDraft>) -> Unit = {},
    onUndo: suspend () -> Unit = {},
): List<SetDraft> {
    val sets = initial.toMutableList()
    fun keyboard() = if (sets.isEmpty()) dialogKeyboard(exit = exit) else dialogKeyboard(DONE, UNDO_SET, exit = exit)
    val saved = sets.lastOrNull()?.let {
        "Подходов в упражнении: ${sets.size}. Последний ${Format.setLine(sets.size, it.weightKg, it.reps, it.restSec, it.rpe, it.durationSec)}\n\n"
    }.orEmpty()
    val prompt = listOf(boldTextSource(name), regularTextSource("\n\n${saved}Введите подходы, каждый с новой строки.\n\n")) +
        setExamples + regularTextSource("\n\nПосле последнего подхода нажмите «Готово».")
    sendTextMessage(chat, entities = prompt, replyMarkup = keyboard())
    while (true) {
        val raw = awaitText(userId)
        if (raw.equals(DONE, ignoreCase = true)) {
            if (sets.isNotEmpty()) return sets
            sendTextMessage(chat, "Сначала введите хотя бы один подход, например «60 10».")
            continue
        }
        if (raw == UNDO_SET) {
            onUndo()
            sets.removeLastOrNull()
        } else {
            val lines = raw.lines().withIndex().filter { it.value.isNotBlank() }
            val parsed = lines.map { Parsing.setOrNull(it.value) }
            val invalid = parsed.indexOfFirst { it == null }
            if (lines.isEmpty() || invalid >= 0) {
                val lineNumber = if (invalid >= 0) lines[invalid].index + 1 else 1
                sendTextMessage(chat, entities = listOf(regularTextSource("Проверьте строку $lineNumber. Исправьте и отправьте сообщение целиком.\n\n")) + setExamples)
                continue
            }
            val batch = parsed.filterNotNull()
            onAppend(batch)
            sets.addAll(batch)
        }
        val last = sets.lastOrNull()?.let {
            Format.setLine(sets.size, it.weightKg, it.reps, it.restSec, it.rpe, it.durationSec)
        }
        val count = "Подходов в упражнении: ${sets.size}."
        sendTextMessage(chat, if (last == null) count else "$count\nПоследний $last", replyMarkup = keyboard())
    }
}

// --- Редактирование тренировки ----------------------------------------------

/** Меню правки тренировки: подходы, упражнения, мета, удаление. Работает до «Готово». */
private suspend fun BehaviourContext.editWorkout(
    chat: Chat,
    userId: Long,
    senderId: Long,
    sessionId: Long,
    catalog: CatalogService,
    workouts: WorkoutService,
) {
    while (true) {
        val keyboard = inlineKeyboard {
            row { dataButton("✏️ Изменить подход", "w:sets") }
            row { dataButton(addExerciseButtonLabel, "w:addex") }
            row { dataButton("🗑 Удалить упражнение", "w:delex") }
            row { dataButton("📅 Дата", "w:date") }
            row { dataButton("⏱ Длительность", "w:duration") }
            row { dataButton("📝 Заметки", "w:note") }
            row { dataButton("🗑 Удалить тренировку", "w:del") }
            row { dataButton("✅ Готово", "w:done") }
        }
        val editPrompt = sendTextMessage(chat, "Что изменить?", replyMarkup = keyboard)
        when (awaitCallbackData(userId, editPrompt.messageId)) {
            "w:sets" -> editSet(chat, userId, senderId, sessionId, workouts)
            "w:date" -> {
                val current = workouts.getSession(senderId, sessionId).performedAt
                editSessionField(chat, userId, "Дата тренировки · ${Format.date(current)}\nВведите дату в формате ДД.ММ.ГГГГ.", "Сегодня", "Вчера") { raw ->
                    val date = Parsing.dateOrNull(raw, LocalDate.now()) ?: throw ValidationException("Введите существующую дату в формате ДД.ММ.ГГГГ.")
                    workouts.updateDate(senderId, sessionId, date)
                }
            }
            "w:duration" -> {
                val current = workouts.getSession(senderId, sessionId).durationMin
                editSessionField(chat, userId, "Длительность тренировки · ${current?.let { "$it мин" } ?: "не указана"}\nВведите число минут.", CLEAR) { raw ->
                    val minutes = if (raw == CLEAR || raw == "-") null else raw.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw ValidationException("Введите положительное целое число минут.")
                    workouts.updateDuration(senderId, sessionId, minutes)
                }
            }
            "w:note" -> {
                val current = workouts.getSession(senderId, sessionId).note
                editSessionField(chat, userId, "Заметки к тренировке · ${current ?: "нет записей"}\nВведите новый текст.", CLEAR) { raw ->
                    val note = raw.takeUnless { it == CLEAR || it == "-" }
                    workouts.updateNote(senderId, sessionId, note)
                }
            }
            "w:addex" -> {
                val exercise = collectExercise(chat, userId, senderId, catalog)
                chooseReply(chat, userId, "${exercise.exerciseName} · подходов: ${exercise.sets.size}", "Сохранить упражнение")
                workouts.addExercise(senderId, sessionId, exercise)
                sendTextMessage(chat, "Добавлено: ${exercise.exerciseName}.", replyMarkup = dialogKeyboard())
            }
            "w:delex" -> {
                val detail = workouts.getDetail(senderId, sessionId)
                val ex = chooseWorkoutExercise(chat, userId, detail) ?: continue
                chooseReply(chat, userId, "Удалить упражнение вместе с его подходами?", "Удалить упражнение")
                workouts.deleteExercise(senderId, ex)
                sendTextMessage(chat, "Упражнение удалено.", replyMarkup = dialogKeyboard())
            }
            "w:del" -> {
                val deletePrompt = sendTextMessage(
                    chat,
                    "Удалить тренировку целиком?",
                    replyMarkup = yesNoKeyboard("wdel:yes", "wdel:no", yesLabel = "Да, удалить", noLabel = "Нет"),
                )
                if (awaitCallbackData(userId, deletePrompt.messageId) == "wdel:yes") {
                    workouts.deleteSession(senderId, sessionId)
                    sendTextMessage(chat, "🗑 Тренировка удалена.", replyMarkup = trainingKeyboard)
                    return
                }
            }
            else -> {
                sendCard(chat, Format.workoutDetail(workouts.getDetail(senderId, sessionId)), replyMarkup = trainingKeyboard)
                return
            }
        }
    }
}

private suspend fun BehaviourContext.editSessionField(
    chat: Chat,
    userId: Long,
    prompt: String,
    vararg choices: String,
    save: suspend (String) -> Unit,
) {
    val keep = "Оставить"
    sendCard(chat, prompt, replyMarkup = dialogKeyboard(*choices, keep))
    while (true) {
        val raw = awaitText(userId)
        if (raw == keep || raw == "=") {
            sendTextMessage(chat, "Текущее значение сохранено.", replyMarkup = dialogKeyboard())
            return
        }
        try {
            save(raw)
        } catch (e: ValidationException) {
            sendTextMessage(chat, e.message.orEmpty())
            continue
        }
        sendTextMessage(chat, "✅ Тренировка обновлена.", replyMarkup = dialogKeyboard())
        return
    }
}

/** Выбор конкретного подхода и ввод новых значений. */
private suspend fun BehaviourContext.editSet(
    chat: Chat,
    userId: Long,
    senderId: Long,
    sessionId: Long,
    workouts: WorkoutService,
) {
    val detail = workouts.getDetail(senderId, sessionId)
    val flatSets = detail.exercises.flatMap { ews -> ews.sets.map { ews.exercise to it } }
    if (flatSets.isEmpty()) {
        sendTextMessage(chat, "В тренировке пока нет подходов.")
        return
    }
    val setId = chooseItem(chat, userId, "Какой подход изменить?", flatSets.map { (ex, set) ->
        val label = "${ex.exerciseName} · ${Format.setLine(set.setNumber, set.weightKg, set.reps, set.restSec, set.rpe, set.durationSec)}"
        label.take(64) to set.id.toString()
    }).toLong()

    sendTextMessage(chat, entities = listOf(regularTextSource("Введите новый результат подхода.\n\n")) + setExamples, replyMarkup = dialogKeyboard())
    var parsed = Parsing.setOrNull(awaitText(userId))
    while (parsed == null) {
        sendTextMessage(chat, entities = listOf(regularTextSource("Введите один подход.\n\n")) + setExamples)
        parsed = Parsing.setOrNull(awaitText(userId))
    }
    workouts.updateSet(senderId, setId, parsed)
    sendTextMessage(chat, "✅ Подход обновлён.")
}

/** Выбор упражнения тренировки, возвращает его id. */
private suspend fun BehaviourContext.chooseWorkoutExercise(
    chat: Chat,
    userId: Long,
    detail: WorkoutDetail,
): Long? {
    if (detail.exercises.isEmpty()) {
        sendTextMessage(chat, "В тренировке нет упражнений.")
        return null
    }
    return chooseItem(chat, userId, "Выберите упражнение.",
        detail.exercises.map { it.exercise.exerciseName to it.exercise.id.toString() }).toLong()
}
