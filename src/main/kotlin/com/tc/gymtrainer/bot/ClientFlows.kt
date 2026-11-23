package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.validateProfile
import com.tc.gymtrainer.bot.platform.ClientProfile
import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.Sex
import com.tc.gymtrainer.bot.platform.ValidationException
import com.tc.gymtrainer.bot.platform.ClientService
import com.tc.gymtrainer.bot.platform.MeasurementInput
import com.tc.gymtrainer.bot.platform.MeasurementService
import com.tc.gymtrainer.bot.platform.WorkoutService
import com.tc.gymtrainer.bot.platform.validateMeasurement
import com.tc.gymtrainer.bot.platform.Trainer
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.utils.row
import java.time.LocalDate

/** Создание подопечного и открытие его журнала. */
internal suspend fun BehaviourContext.addClient(
    chat: Chat,
    userId: Long,
    trainer: Trainer,
    clients: ClientService,
) {
    val first = askName(chat, userId, "Имя нового подопечного", 253)
    val last = askName(chat, userId, "Фамилия подопечного", 254 - first.length)
    val initial = ClientProfile(first, last)
    val action = chooseReply(chat, userId, "${Format.profile(initial)}\nСохранить подопечного или заполнить дополнительные данные?", SAVE, "Редактировать")
    val draft = if (action == SAVE) ProfileDraft(initial) else collectProfile(chat, userId, initial, clients.goals(trainer.telegramUserId))
    val client = clients.create(trainer.telegramUserId, draft.profile, draft.measurement)
    val card = Format.clientCard(client, draft.measurement?.weightKg, draft.measurement?.heightCm)
    sendTextMessage(chat, "Подопечный добавлен.", replyMarkup = trainingKeyboard)
    sendCard(chat, card, replyMarkup = clientActionsKeyboard(client.id))
}

internal suspend fun BehaviourContext.showClients(
    chat: Chat, trainer: Trainer, clients: ClientService, page: Int = 0,
) {
    val list = clients.list(trainer.telegramUserId, includeArchived = true)
    val title = if (list.isEmpty()) "У вас пока нет подопечных." else "Ваши подопечные"
    sendListPage(chat, title, list.map { Format.clientLine(it) to "cl:view:${it.id}" },
        listOf("➕ ${TrainingAction.ADD_CLIENT.label}" to "cl:add"), page) { "cl:page:$it" }
}

internal suspend fun BehaviourContext.showClientCard(
    chat: Chat, senderId: Long, client: Client, measurements: MeasurementService, workouts: WorkoutService,
) {
    val current = measurements.current(senderId, client.id)
    val card = Format.clientCard(client, current["weightKg"]?.value, current["heightCm"]?.value)
    val active = workouts.activeForClient(senderId, client.id)
    sendCard(chat, if (active == null) card else "$card\n\nИдёт тренировка от ${Format.date(active.performedAt)}.",
        replyMarkup = clientActionsKeyboard(client.id))
}

private suspend fun BehaviourContext.askName(chat: Chat, userId: Long, prompt: String, maxLength: Int): String {
    sendTextMessage(chat, prompt, replyMarkup = dialogKeyboard())
    while (true) {
        val name = awaitText(userId)
        if (name !in listOf("-", SKIP, CLEAR) && name.length in 1..maxLength) return name
        sendTextMessage(chat, "Введите имя или фамилию длиной до $maxLength символов.")
    }
}

private enum class ProfileField(val label: String) {
    AGE("Возраст"), SEX("Пол"), GOAL("Цель"), NOTES("Заметки"), TELEGRAM("Ник Telegram"),
    FIRST("Имя"), LAST("Фамилия");

    fun apply(profile: ClientProfile, value: String?): ClientProfile = when (this) {
        FIRST -> profile.copy(firstName = requireNotNull(value))
        LAST -> profile.copy(lastName = requireNotNull(value))
        SEX -> profile.copy(sex = value?.let { raw -> Sex.entries.find { it.label == raw }
            ?: throw ValidationException("Выберите пол кнопкой.") })
        AGE -> profile.copy(age = value?.let { it.toIntOrNull() ?: throw ValidationException("Введите возраст целым числом.") })
        TELEGRAM -> profile.copy(telegramUsername = value)
        GOAL -> profile.copy(goal = value)
        NOTES -> profile.copy(notes = value)
    }
}

private data class ProfileDraft(
    val profile: ClientProfile,
    val measurement: MeasurementInput? = null,
)

private suspend fun BehaviourContext.collectProfile(
    chat: Chat, userId: Long, initial: ClientProfile, goals: List<String>,
): ProfileDraft {
    var profile = initial
    var measurement = MeasurementInput(LocalDate.now())
    val metrics = listOf(Metric.WEIGHT, Metric.HEIGHT)
    val menu = dialogKeyboard(*(metrics.map { it.label } + ProfileField.entries.filter { it != ProfileField.GOAL || goals.isNotEmpty() }.map { it.label } + SAVE).toTypedArray())
    while (true) {
        val card = Format.profile(profile, measurement.weightKg, measurement.heightCm)
        sendCard(chat, "$card\n\nВыберите поле или нажмите «Сохранить».", replyMarkup = menu)
        val action = awaitText(userId)
        if (action == SAVE) return ProfileDraft(profile, measurement.takeIf { it.weightKg != null || it.heightCm != null })
        val metric = metrics.find { it.label == action }
        if (metric != null) {
            measurement = readProfileMeasurement(chat, userId, metric, measurement)
            continue
        }
        val field = ProfileField.entries.find { it.label == action } ?: continue
        val choices = when (field) {
            ProfileField.SEX -> Sex.entries.map { it.label }
            ProfileField.GOAL -> goals
            else -> emptyList()
        }
        val required = field == ProfileField.FIRST || field == ProfileField.LAST
        val actions = choices + if (required) listOf(SKIP) else listOf(SKIP, CLEAR)
        val prompt = when (field) {
            ProfileField.TELEGRAM -> "Введите ник Telegram, например @username."
            ProfileField.AGE -> "Сколько полных лет подопечному?"
            else -> field.label
        }
        sendTextMessage(chat, prompt, replyMarkup = dialogKeyboard(*actions.toTypedArray()))
        while (true) {
            val raw = awaitText(userId)
            if (raw == SKIP) break
            if (required && (raw.isBlank() || raw == CLEAR || raw == "-")) {
                sendTextMessage(chat, "Поле обязательно. Введите значение или нажмите «Пропустить».")
                continue
            }
            val value = if (raw == CLEAR) null else raw
            try {
                if (field == ProfileField.GOAL && value != null && value !in goals) throw ValidationException("Выберите цель кнопкой.")
                profile = validateProfile(field.apply(profile, value))
                break
            } catch (e: ValidationException) {
                sendTextMessage(chat, e.message.orEmpty())
            }
        }
    }
}

private suspend fun BehaviourContext.readProfileMeasurement(
    chat: Chat, userId: Long, metric: Metric, draft: MeasurementInput,
): MeasurementInput {
    val reset = "Сбросить ввод"
    val keyboard = if (metric.read(draft) == null) dialogKeyboard(SKIP) else dialogKeyboard(SKIP, reset)
    sendTextMessage(chat, "${metric.label} · введите значение для замера за сегодня.", replyMarkup = keyboard)
    while (true) {
        val raw = awaitText(userId)
        if (raw == SKIP) return draft
        if (raw == reset) return metric.write(draft, null)
        try {
            val value = Parsing.decimalOrNull(raw) ?: throw ValidationException("Введите число.")
            return metric.write(draft, value).also(::validateMeasurement)
        } catch (e: ValidationException) {
            sendTextMessage(chat, e.message.orEmpty())
        }
    }
}

internal fun clientActionsKeyboard(clientId: Long) = inlineKeyboard {
    row {
        dataButton("Тренировки", "w:hist:$clientId")
        dataButton("Замеры", "m:hist:$clientId")
    }
}
