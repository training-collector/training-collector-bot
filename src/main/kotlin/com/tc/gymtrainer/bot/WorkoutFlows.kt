package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.ExerciseCatalogItem
import com.tc.gymtrainer.bot.platform.ExerciseDraft
import com.tc.gymtrainer.bot.platform.SetDraft
import com.tc.gymtrainer.bot.platform.ValidationException
import com.tc.gymtrainer.bot.platform.WorkoutDetail
import com.tc.gymtrainer.bot.platform.WorkoutSession
import com.tc.gymtrainer.bot.platform.WorkoutStage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.message.textsources.boldTextSource
import dev.inmo.tgbotapi.types.message.textsources.regularTextSource
import dev.inmo.tgbotapi.utils.row

/**
 * Запись подходов, история и редактирование тренировок подопечного.
 */
internal suspend fun Dialog.recordWorkout(client: Client, existingSessionId: Long? = null) {
    val sessionId = existingSessionId ?: api.startOrResume(client.id).id
    // The chosen exercise and the saved sets are on the platform. From these points the dialog
    // continues without its earlier steps, so its stored draft stays small.
    fun restart(): Nothing = throw RestartDialog("workout", "${client.id}:$sessionId")
    try {
        while (true) {
            val detail = api.workout(sessionId)
            when (detail.session.stage) {
                WorkoutStage.CHOOSE_EXERCISE -> {
                    val item = selectCatalogExercise(client)
                    api.selectExercise(sessionId, item.id)
                    restart()
                }
                WorkoutStage.ENTER_SETS -> {
                    val (exercise, sets) = detail.exercises.last()
                    collectSets(exercise.exerciseName,
                        initial = sets.map { SetDraft(it.weightKg, it.reps, it.restSec, it.rpe, it.durationSec) },
                        exit = TrainingAction.MENU,
                        onAppend = { api.appendSets(sessionId, exercise.id, it) },
                        onUndo = { api.undoSet(sessionId, exercise.id) })
                    api.finishExercise(sessionId, exercise.id)
                }
                WorkoutStage.REVIEW -> {
                    val summary = detail.exercises.joinToString("\n") { "${it.exercise.exerciseName} · подходов: ${it.sets.size}" }
                    val actions = listOf("Ещё упражнение" to "more") + finishAndCancelButtons(detail.session)
                    chooseItem("$summary\nУпражнений в тренировке: ${detail.exercises.size}", actions, BACK_TO_WORKOUTS to "w:hist:${client.id}")
                    api.anotherExercise(sessionId)
                    restart()
                }
                WorkoutStage.COMPLETED -> {
                    sendCard("Тренировка уже завершена.\n${client.fullName}", replyMarkup = clientActionsKeyboard(client.id))
                    return
                }
            }
        }
    } catch (_: FlowCancelled) {
        send("Тренировка сохранена. Продолжить её можно в разделе «Тренировки» подопечного.", replyMarkup = trainingKeyboard)
    }
}

internal suspend fun Dialog.finishWorkout(client: Client, sessionId: Long, choice: String?) {
    val minutes = when (choice) {
        null, "duration", "custom" -> {
            val prompt = when (choice) {
                null -> "Длительность тренировки"
                "duration" -> "Сколько минут?"
                else -> "Введите продолжительность тренировки в минутах целым числом. Например, 75."
            }
            val keyboard = finishDurationKeyboard(sessionId, client.id, choice)
            sendCard("${client.fullName}\n\n$prompt", replyMarkup = keyboard)
            if (choice != "custom") return
            var value = positiveMinutes(awaitText())
            while (value == null) {
                send("Введите положительное целое число минут, например 75.", replyMarkup = keyboard)
                value = positiveMinutes(awaitText())
            }
            value
        }
        "skip" -> null
        else -> positiveMinutes(choice) ?: throw ValidationException("Введите положительное целое число минут.")
    }
    val detail = api.finishWorkout(sessionId, minutes)
    send("Тренировка завершена.", replyMarkup = trainingKeyboard)
    sendCard("${client.fullName}\n\n${Format.workoutDetail(detail, date(detail.session.performedAt))}",
        replyMarkup = clientActionsKeyboard(client.id))
}

private fun positiveMinutes(text: String): Int? = text.toIntOrNull()?.takeIf { it > 0 }

private fun finishDurationKeyboard(sessionId: Long, clientId: Long, choice: String?) = inlineKeyboard {
    val finish = "w:finish:$sessionId"
    if (choice == null) row { dataButton("Указать продолжительность", "$finish:duration") }
    if (choice == "duration") {
        listOf(30, 45, 50, 60, 90).chunked(3).forEach { options ->
            row { options.forEach { minutes -> dataButton("$minutes мин", "$finish:$minutes") } }
        }
        row { dataButton("Свой вариант", "$finish:custom") }
    }
    row { dataButton("Завершить сразу", "$finish:skip") }
    row {
        when (choice) {
            null -> dataButton(BACK_TO_WORKOUTS, "w:hist:$clientId")
            "duration" -> dataButton("$BACK_ICON К завершению", finish)
            else -> dataButton("$BACK_ICON К выбору длительности", "$finish:duration")
        }
    }
}

private fun finishAndCancelButtons(session: WorkoutSession) = listOf(
    FINISH_WORKOUT to "w:finish:${session.id}",
    "Отменить тренировку" to "w:cancel:${session.clientId}:${session.id}",
)

internal const val WORKOUT_NO_LONGER_ACTIVE = "Эта тренировка уже не активна."

internal suspend fun Dialog.confirmWorkoutCancellation(client: Client, sessionId: Long) {
    val active = api.activeWorkout(client.id)
    if (active?.id != sessionId) {
        showWorkoutMenu(client, notice = WORKOUT_NO_LONGER_ACTIVE)
        return
    }
    sendCard("${client.fullName} · ${date(active.performedAt)}\n\nОтменить тренировку? Запись и все её подходы будут удалены.",
        replyMarkup = inlineKeyboard {
            row { dataButton("$BACK_ICON Оставить тренировку", "w:hist:${client.id}") }
            row { dataButton("Да, отменить", "w:discard:${client.id}:$sessionId") }
        })
}

internal suspend fun Dialog.showWorkoutMenu(client: Client, notice: String? = null) {
    val active = api.activeWorkout(client.id)
    val title = listOfNotNull("Тренировки · ${client.fullName}", notice).joinToString("\n\n")
    sendCard(title, replyMarkup = inlineKeyboard {
        if (active == null) {
            row { dataButton(TrainingAction.WORKOUT.label, "w:add:${client.id}") }
        } else {
            row { dataButton(RESUME_WORKOUT, "w:resume:${active.id}") }
            finishAndCancelButtons(active).forEach { (label, data) -> row { dataButton(label, data) } }
        }
        row { dataButton(PAST_WORKOUTS, "w:hist:${client.id}:0") }
        row { dataButton(BACK_TO_CLIENT, "cl:view:${client.id}") }
    })
}

internal suspend fun Dialog.showWorkoutHistory(client: Client, page: Int = 0) {
    val sessions = api.history(client.id)
    val title = "$PAST_WORKOUTS · ${client.fullName}" + if (sessions.isEmpty()) "\n\nЗавершённых тренировок пока нет." else ""
    sendListPage(title, sessions.map { Format.sessionLine(it, date(it.performedAt)) to "w:view:${it.id}" }, emptyList(), page,
        BACK_TO_WORKOUTS to "w:hist:${client.id}") { "w:hist:${client.id}:$it" }
}

internal suspend fun Dialog.showCompletedWorkout(sessionId: Long) {
    val detail = api.workout(sessionId)
    if (detail.session.stage != WorkoutStage.COMPLETED) throw ValidationException("Тренировка ещё идёт. Откройте её в разделе «Тренировки».")
    sendCard(Format.workoutDetail(detail, date(detail.session.performedAt)))
    editWorkout(detail.session)
}

private suspend fun Dialog.selectCatalogExercise(client: Client? = null): ExerciseCatalogItem {
    val items = api.exercises()
    val byGroup = items.groupBy { it.muscleGroupId }
    val groups = api.groups().filter { it.id in byGroup }
    val groupChoices = groups.map { it.name to it.id.toString() }
    val title = if (groups.isEmpty()) "Администратор ещё не опубликовал упражнения." else "Выберите группу мышц"
    if (groups.isEmpty() && client == null) throw ValidationException(title)
    val prompt = if (client == null) title else "Идёт тренировка · ${client.fullName}\n$title\n\n«$CLOSE_WORKOUT» вернёт в раздел «Тренировки»."
    val footer = if (client == null) TrainingAction.CANCEL.label to "cancel" else "$BACK_ICON $CLOSE_WORKOUT" to "w:hist:${client.id}"
    while (true) {
        // The footer of an active workout is a menu button: it starts a new dialog and never returns here.
        val groupId = chooseItem(prompt, groupChoices, footer).toLongOrNull() ?: throw ChangedDialog()
        val choices = byGroup[groupId] ?: throw ChangedDialog()
        val selection = chooseItem("Выберите упражнение · ${groups.first { it.id == groupId }.name}",
            choices.map { it.name to it.id.toString() }, BACK_TO_CATEGORIES to "groups")
        if (selection != "groups") return choices.first { it.id.toString() == selection }
    }
}

private suspend fun Dialog.collectExercise(): ExerciseDraft {
    val item = selectCatalogExercise()
    return ExerciseDraft(item.id, item.name, collectSets(item.name))
}

private const val UNDO_SET = "Убрать последний подход"

private suspend fun Dialog.collectSets(
    name: String, initial: List<SetDraft> = emptyList(),
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
    send(prompt, replyMarkup = keyboard())
    while (true) {
        val raw = awaitText()
        if (raw.equals(DONE, ignoreCase = true)) {
            if (sets.isNotEmpty()) return sets
            send("Сначала введите хотя бы один подход, например «60 10».")
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
                send(listOf(regularTextSource("Проверьте строку $lineNumber. Исправьте и отправьте сообщение целиком.\n\n")) + setExamples)
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
        send(if (last == null) count else "$count\nПоследний $last", replyMarkup = keyboard())
    }
}

// --- Редактирование тренировки ----------------------------------------------

/** Меню правки тренировки: подходы, упражнения, мета, удаление. Работает до «Готово». */
private suspend fun Dialog.editWorkout(session: WorkoutSession) {
    val sessionId = session.id
    while (true) {
        val keyboard = inlineKeyboard {
            row { dataButton("✏️ Изменить подход", "w:sets") }
            row { dataButton(addExerciseButtonLabel, "w:addex") }
            row { dataButton("🗑 Удалить упражнение", "w:delex") }
            row { dataButton("📅 Дата", "w:date") }
            row { dataButton("🕒 Длительность", "w:duration") }
            row { dataButton("📝 Заметки", "w:note") }
            row { dataButton("🗑 Удалить тренировку", "w:del") }
            row { dataButton("✅ Готово", "w:done") }
            row { dataButton("$BACK_ICON К прошедшим тренировкам", "w:hist:${session.clientId}:0") }
        }
        val editPrompt = send("Что изменить?", replyMarkup = keyboard)
        when (awaitCallback(editPrompt)) {
            "w:sets" -> editSet(sessionId)
            "w:date" -> {
                val current = api.workout(sessionId).session
                editSessionField("Дата тренировки · ${date(current.performedAt)}\nВведите дату в формате ДД.ММ.ГГГГ.", "Сегодня", "Вчера") { raw ->
                    val day = Parsing.dateOrNull(raw, today) ?: throw ValidationException("Введите существующую дату в формате ДД.ММ.ГГГГ.")
                    // Read again right before the write: the other fields may have been edited on the site meanwhile.
                    val latest = api.workout(sessionId).session
                    api.updateWorkout(latest.copy(performedAt = onDate(latest.performedAt, day)))
                }
            }
            "w:duration" -> {
                val current = api.workout(sessionId).session.durationMin
                editSessionField("Длительность тренировки · ${current?.let { "$it мин" } ?: "не указана"}\nВведите число минут.", CLEAR) { raw ->
                    val minutes = if (raw == CLEAR || raw == "-") null else positiveMinutes(raw)
                        ?: throw ValidationException("Введите положительное целое число минут.")
                    api.updateWorkout(api.workout(sessionId).session.copy(durationMin = minutes))
                }
            }
            "w:note" -> {
                val current = api.workout(sessionId).session.note
                editSessionField("Заметки к тренировке · ${current ?: "нет записей"}\nВведите новый текст.", CLEAR) { raw ->
                    api.updateWorkout(api.workout(sessionId).session.copy(note = raw.takeUnless { it == CLEAR || it == "-" }))
                }
            }
            "w:addex" -> {
                val exercise = collectExercise()
                chooseReply("${exercise.exerciseName} · подходов: ${exercise.sets.size}", "Сохранить упражнение")
                api.addExercise(sessionId, exercise)
                send("Добавлено: ${exercise.exerciseName}.", replyMarkup = dialogKeyboard())
            }
            "w:delex" -> {
                val ex = chooseWorkoutExercise(api.workout(sessionId)) ?: continue
                chooseReply("Удалить упражнение вместе с его подходами?", "Удалить упражнение")
                api.deleteExercise(ex)
                send("Упражнение удалено.", replyMarkup = dialogKeyboard())
            }
            "w:del" -> {
                val deletePrompt = send("Удалить тренировку целиком?",
                    replyMarkup = yesNoKeyboard("wdel:yes", "wdel:no", yesLabel = "Да, удалить", noLabel = "Нет"))
                if (awaitCallback(deletePrompt) == "wdel:yes") {
                    api.deleteWorkout(sessionId)
                    send("🗑 Тренировка удалена.", replyMarkup = trainingKeyboard)
                    return
                }
            }
            else -> {
                val detail = api.workout(sessionId)
                sendCard(Format.workoutDetail(detail, date(detail.session.performedAt)), replyMarkup = trainingKeyboard)
                return
            }
        }
    }
}

private suspend fun Dialog.editSessionField(prompt: String, vararg choices: String, save: suspend (String) -> Unit) {
    val keep = "Оставить"
    sendCard(prompt, replyMarkup = dialogKeyboard(*choices, keep))
    while (true) {
        val raw = awaitText()
        if (raw == keep || raw == "=") {
            send("Текущее значение сохранено.", replyMarkup = dialogKeyboard())
            return
        }
        try {
            save(raw)
        } catch (e: ValidationException) {
            send(e.message.orEmpty())
            continue
        }
        send("✅ Тренировка обновлена.", replyMarkup = dialogKeyboard())
        return
    }
}

/** A button label of at most 64 characters. The set stays whole; a long exercise name is shortened. */
internal fun setButtonLabel(exerciseName: String, setLine: String): String {
    val room = 64 - setLine.length - 3
    val name = if (exerciseName.length <= room) exerciseName else exerciseName.take((room - 1).coerceAtLeast(0)).trimEnd() + "…"
    return if (room < 4) setLine.take(64) else "$name · $setLine"
}

/** Выбор конкретного подхода и ввод новых значений. */
private suspend fun Dialog.editSet(sessionId: Long) {
    val detail = api.workout(sessionId)
    val flatSets = detail.exercises.flatMap { ews -> ews.sets.map { ews.exercise to it } }
    if (flatSets.isEmpty()) {
        send("В тренировке пока нет подходов.")
        return
    }
    val setId = chooseItem("Какой подход изменить?", flatSets.map { (ex, set) ->
        setButtonLabel(ex.exerciseName, Format.setLine(set.setNumber, set.weightKg, set.reps, set.restSec, set.rpe, set.durationSec)) to set.id.toString()
    }).toLong()

    send(listOf(regularTextSource("Введите новый результат подхода.\n\n")) + setExamples, replyMarkup = dialogKeyboard())
    var parsed = Parsing.setOrNull(awaitText())
    while (parsed == null) {
        send(listOf(regularTextSource("Введите один подход.\n\n")) + setExamples)
        parsed = Parsing.setOrNull(awaitText())
    }
    api.updateSet(setId, parsed)
    send("✅ Подход обновлён.")
}

/** Выбор упражнения тренировки, возвращает его id. */
private suspend fun Dialog.chooseWorkoutExercise(detail: WorkoutDetail): Long? {
    if (detail.exercises.isEmpty()) {
        send("В тренировке нет упражнений.")
        return null
    }
    return chooseItem("Выберите упражнение.", detail.exercises.map { it.exercise.exerciseName to it.exercise.id.toString() }).toLong()
}
