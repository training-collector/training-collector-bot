package com.tc.gymtrainer.bot

import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.message.textsources.boldTextSource
import dev.inmo.tgbotapi.types.message.textsources.codeTextSource
import dev.inmo.tgbotapi.types.message.textsources.regularTextSource
import dev.inmo.tgbotapi.utils.row

internal enum class TrainingAction(val command: String, val label: String) {
    WORKOUT("workout", "Записать тренировку"),
    HISTORY("history", "История тренировок"),
    CLIENTS("clients", "Подопечные"),
    EXERCISES("exercises", "Упражнения"),
    ADD_CLIENT("add_client", "Добавить подопечного"),
    MEASURE("measure", "Замеры"),
    HELP("help", "Помощь"),
    MENU("menu", "Главное меню"),
    CANCEL("cancel", "Отмена");

    companion object {
        fun from(text: String, botUsername: String? = null): TrainingAction? {
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

internal const val SKIP = "Пропустить"
internal const val CLEAR = "Очистить поле"
internal const val SAVE = "Сохранить"
internal const val DONE = "Готово"
internal const val FINISH_WORKOUT = "Завершить тренировку"
internal const val addExerciseButtonLabel = "➕ Добавить упражнение"

internal fun dialogKeyboard(vararg actions: String, exit: TrainingAction = TrainingAction.CANCEL, exitFirst: Boolean = false) = replyKeyboard(resizeKeyboard = true) {
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
