package com.tc.gymtrainer.bot

import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.message.textsources.boldTextSource
import dev.inmo.tgbotapi.types.message.textsources.codeTextSource
import dev.inmo.tgbotapi.types.message.textsources.regularTextSource
import dev.inmo.tgbotapi.utils.row

internal const val BACK_ICON = "↩️"

internal enum class TrainingAction(val command: String, val label: String) {
    WORKOUT("workout", "Записать тренировку"),
    HISTORY("history", "История тренировок"),
    CLIENTS("clients", "Подопечные"),
    EXERCISES("exercises", "Упражнения"),
    ADD_CLIENT("add_client", "Добавить подопечного"),
    MEASURE("measure", "Замеры"),
    HELP("help", "Помощь"),
    MENU("menu", "$BACK_ICON Главное меню"),
    CANCEL("cancel", "Отмена");

    companion object {
        fun from(text: String, botUsername: String? = null): TrainingAction? {
            if (text == "Главное меню") return MENU
            if (!text.startsWith('/')) return entries.find { it.label == text }
            val command = text.removePrefix("/")
            if ('@' in command && botUsername != null && !command.substringAfter('@').equals(botUsername, ignoreCase = true)) return null
            return entries.find { it.command == command.substringBefore('@') }
        }
    }
}

internal val trainingKeyboard = replyKeyboard(resizeKeyboard = true, persistent = true) {
    row { simpleButton(TrainingAction.CLIENTS.label) }
    row { simpleButton(TrainingAction.EXERCISES.label) }
}

internal val menuCallback = Regex(
    """cl:add|(?:cl|ex):(?:page:\d+|view:[1-9]\d*)|w:(?:add|view|resume):[1-9]\d*|w:hist:[1-9]\d*(?::\d+)?""" +
        """|w:finish:[1-9]\d*(?::(?:duration|custom|skip|[1-9]\d*))?""" +
        """|w:(?:cancel|discard):[1-9]\d*:[1-9]\d*""" +
        """|ex:group:[1-9]\d*(?::\d+)?|m:(?:view:[1-9]\d*|add(?::[1-9]\d*)?|hist(?::[1-9]\d*(?::\d+)?)?|close)"""
)

internal const val SKIP = "Пропустить"
internal const val CLEAR = "Очистить поле"
internal const val SAVE = "Сохранить"
internal const val DONE = "Готово"
internal const val FINISH_WORKOUT = "Завершить тренировку"
internal const val CLOSE_WORKOUT = "Закрыть тренировку"
internal const val RESUME_WORKOUT = "Продолжить тренировку"
internal const val PAST_WORKOUTS = "Прошедшие тренировки"
internal const val BACK_TO_CATEGORIES = "$BACK_ICON Вернуться к категориям"
internal const val BACK_TO_CLIENT = "$BACK_ICON К подопечному"
internal const val BACK_TO_WORKOUTS = "$BACK_ICON К тренировкам"
internal const val addExerciseButtonLabel = "➕ Добавить упражнение"

internal fun dialogKeyboard(vararg actions: String, exit: TrainingAction = TrainingAction.CANCEL, exitFirst: Boolean = false) =
    replyKeyboard(resizeKeyboard = true, oneTimeKeyboard = exit == TrainingAction.MENU) {
        if (exitFirst) row { simpleButton(exit.label) }
        actions.toList().chunked(2).forEach { pair -> row { pair.forEach { simpleButton(it) } } }
        if (!exitFirst) row { simpleButton(exit.label) }
    }

internal val setExamples = listOf(
    boldTextSource("На повторения"), regularTextSource("\n"),
    codeTextSource("60 10"), regularTextSource(" — 60 кг × 10 повторений\n"),
    codeTextSource("0 12"), regularTextSource(" — 12 повторений со своим весом\n\n"),
    boldTextSource("На время"), regularTextSource("\n"),
    codeTextSource("45с"), regularTextSource(" — 45 секунд\n"),
    codeTextSource("16 30с"), regularTextSource(" — 30 секунд с весом 16 кг"),
)

internal val trainingHelp = listOf(
    boldTextSource("Как записать тренировку"),
    regularTextSource("\n\nОткройте «Подопечные», выберите человека и нажмите «Тренировки» → «Записать тренировку».\n\n" +
        "Выберите упражнение. Для каждого подхода отправьте отдельную строку.\n\n"),
) + setExamples + regularTextSource("\n\nПосле ввода нажмите «Готово». Вернуться к начатой записи можно в разделе «Тренировки» подопечного.")
