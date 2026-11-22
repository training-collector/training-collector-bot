package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.BodyMeasurement
import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.ClientService
import com.tc.gymtrainer.bot.platform.MeasurementInput
import com.tc.gymtrainer.bot.platform.MeasurementService
import com.tc.gymtrainer.bot.platform.Trainer
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.utils.row
import com.tc.gymtrainer.bot.platform.validateMeasurement
import com.tc.gymtrainer.bot.platform.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

/** Замеры тела: добавление, история, редактирование, удаление. */
internal suspend fun BehaviourContext.addMeasurement(
    chat: Chat, userId: Long, senderId: Long, client: Client, measurements: MeasurementService,
) {
    val input = collectMeasurement(chat, userId, MeasurementInput(LocalDate.now()), client.fullName)
    val saved = measurements.add(senderId, client.id, input)
    sendCard(chat, "✅ Замер сохранён.\n\n${client.fullName}\n${Format.measurementCard(saved)}", replyMarkup = trainingKeyboard)
}

internal suspend fun BehaviourContext.showMeasurementHistory(
    chat: Chat, senderId: Long, client: Client, measurements: MeasurementService, page: Int = 0,
) {
    val history = measurements.history(senderId, client.id)
    val items = history.map { m ->
        val weight = Format.number(m.weightKg)?.let { " · $it кг" }.orEmpty()
        "${Format.date(m.measuredOn)}$weight" to "m:view:${m.id}"
    }
    val title = if (history.isEmpty()) "${client.fullName} · замеров пока нет." else "История замеров · ${client.fullName}"
    sendListPage(chat, title, items, listOf("Добавить новый замер" to "m:add:${client.id}"), page,
        "К подопечному" to "cl:view:${client.id}") { "m:hist:${client.id}:$it" }
}

internal suspend fun BehaviourContext.showMeasurement(
    chat: Chat, userId: Long, trainer: Trainer, id: Long, clients: ClientService, measurements: MeasurementService,
) {
    val senderId = trainer.telegramUserId
    val measurement = measurements.get(senderId, id)
    val client = clients.getOwned(senderId, measurement.clientId)
    val editPrompt = sendCard(
        chat,
        Format.measurementCard(measurement),
        replyMarkup = inlineKeyboard {
            row { dataButton("✏️ Изменить", "me:edit") }
            row { dataButton("🗑 Удалить", "me:del") }
            row { dataButton("✅ Готово", "me:done") }
        },
    )
    when (awaitCallbackData(userId, editPrompt.messageId)) {
        "me:edit" -> {
            val input = collectMeasurement(chat, userId, measurement.toInput(), client.fullName)
            val updated = measurements.update(senderId, id, input, expected = measurement)
            sendCard(chat, "✅ Обновлено.\n\n${Format.measurementCard(updated)}", replyMarkup = trainingKeyboard)
        }
        "me:del" -> {
            val confirm = sendTextMessage(chat, "Удалить замер за ${Format.date(measurement.measuredOn)}?",
                replyMarkup = inlineKeyboard {
                    row { dataButton("Удалить замер", "me:confirm") }
                    row { dataButton("Отмена", "cancel") }
                })
            if (awaitCallbackData(userId, confirm.messageId) == "me:confirm") {
                measurements.delete(senderId, id)
                sendTextMessage(chat, "Замер удалён.", replyMarkup = trainingKeyboard)
            }
        }
        else -> sendTextMessage(chat, "Готово.", replyMarkup = trainingKeyboard)
    }
}

private fun BodyMeasurement.toInput() = MeasurementInput(
    measuredOn = measuredOn, weightKg = weightKg, bodyFatPct = bodyFatPct, chestCm = chestCm, waistCm = waistCm,
    hipsCm = hipsCm, bicepsCm = bicepsCm, thighCm = thighCm, note = note, heightCm = heightCm, calfCm = calfCm,
    rightBicepsCm = rightBicepsCm, leftBicepsCm = leftBicepsCm, rightThighCm = rightThighCm, leftThighCm = leftThighCm,
    rightCalfCm = rightCalfCm, leftCalfCm = leftCalfCm,
    rightForearmCm = rightForearmCm, leftForearmCm = leftForearmCm,
    backWidthCm = backWidthCm,
)

internal enum class Metric(val label: String, val read: (MeasurementInput) -> BigDecimal?,
                          val write: (MeasurementInput, BigDecimal?) -> MeasurementInput) {
    WEIGHT("Вес, кг", { it.weightKg }, { m, v -> m.copy(weightKg = v) }),
    HEIGHT("Рост, см", { it.heightCm }, { m, v -> m.copy(heightCm = v) }),
    BACK_WIDTH("Ширина спины, см", { it.backWidthCm }, { m, v -> m.copy(backWidthCm = v) }),
    FAT("Жир, %", { it.bodyFatPct }, { m, v -> m.copy(bodyFatPct = v) }),
    CHEST("Обхват груди, см", { it.chestCm }, { m, v -> m.copy(chestCm = v) }),
    WAIST("Обхват талии, см", { it.waistCm }, { m, v -> m.copy(waistCm = v) }),
    HIPS("Обхват ягодиц, см", { it.hipsCm }, { m, v -> m.copy(hipsCm = v) }),
    RIGHT_BICEPS("Обхват правой руки, см", { it.rightBicepsCm }, { m, v -> m.copy(rightBicepsCm = v) }),
    LEFT_BICEPS("Обхват левой руки, см", { it.leftBicepsCm }, { m, v -> m.copy(leftBicepsCm = v) }),
    RIGHT_FOREARM("Обхват правого предплечья, см", { it.rightForearmCm }, { m, v -> m.copy(rightForearmCm = v) }),
    LEFT_FOREARM("Обхват левого предплечья, см", { it.leftForearmCm }, { m, v -> m.copy(leftForearmCm = v) }),
    RIGHT_THIGH("Обхват правого бедра, см", { it.rightThighCm }, { m, v -> m.copy(rightThighCm = v) }),
    LEFT_THIGH("Обхват левого бедра, см", { it.leftThighCm }, { m, v -> m.copy(leftThighCm = v) }),
    RIGHT_CALF("Обхват правой голени, см", { it.rightCalfCm }, { m, v -> m.copy(rightCalfCm = v) }),
    LEFT_CALF("Обхват левой голени, см", { it.leftCalfCm }, { m, v -> m.copy(leftCalfCm = v) }),
    ARM("Обхват руки без стороны, см", { it.bicepsCm }, { m, v -> m.copy(bicepsCm = v) }),
    THIGH("Обхват бедра без стороны, см", { it.thighCm }, { m, v -> m.copy(thighCm = v) }),
    CALF("Обхват голени без стороны, см", { it.calfCm }, { m, v -> m.copy(calfCm = v) })
}

private suspend fun BehaviourContext.collectMeasurement(
    chat: Chat, userId: Long, initial: MeasurementInput, clientName: String,
): MeasurementInput {
    var draft = initial
    val fields = Metric.entries.filter { it !in setOf(Metric.ARM, Metric.THIGH, Metric.CALF) || it.read(initial) != null }
    val menu = replyKeyboard(resizeKeyboard = true) {
        row { simpleButton(SAVE) }
        row { simpleButton(TrainingAction.CANCEL.label) }
        (fields.map { it.label } + listOf("Дата", "Заметка")).chunked(2).forEach { pair ->
            row { pair.forEach { simpleButton(it) } }
        }
    }
    while (true) {
        val summary = buildString {
            appendLine("$clientName · замер за ${Format.date(draft.measuredOn)}")
            for (field in Metric.entries) field.read(draft)?.let { appendLine("${field.label}: ${Format.number(it)}") }
            draft.note?.let { appendLine("Заметка: $it") }
            append("Выберите показатель или нажмите «Сохранить».")
        }
        sendCard(chat, summary, replyMarkup = menu)
        val action = awaitText(userId)
        if (action == SAVE) {
            try {
                validateMeasurement(draft)
                return draft
            } catch (e: ValidationException) {
                sendTextMessage(chat, e.message.orEmpty())
                continue
            }
        }
        val metric = fields.find { it.label == action }
        if (metric == null && action != "Дата" && action != "Заметка") continue
        val keyboard = if (action == "Дата") dialogKeyboard("Сегодня", "Вчера", SKIP, exitFirst = true) else dialogKeyboard(SKIP, CLEAR, exitFirst = true)
        val prompt = when {
            action == "Дата" -> "Введите дату в формате ДД.ММ.ГГГГ или выберите день."
            metric == Metric.BACK_WIDTH -> "Введите ширину спины в сантиметрах. При свободно опущенных руках измерьте расстояние по лопаткам между задними краями подмышек."
            else -> "Введите значение · $action"
        }
        sendTextMessage(chat, prompt, replyMarkup = keyboard)
        while (true) {
            val raw = awaitText(userId)
            if (raw == SKIP) break
            try {
                draft = when {
                    metric != null -> {
                        val value = if (raw == CLEAR) null else Parsing.decimalOrNull(raw)
                            ?: throw ValidationException("Введите число, например 72,5.")
                        metric.write(draft, value).also { if (value != null) validateMeasurement(it) }
                    }
                    action == "Дата" -> draft.copy(measuredOn = Parsing.dateOrNull(raw, LocalDate.now())
                        ?: throw ValidationException("Введите существующую дату в формате ДД.ММ.ГГГГ."))
                    else -> draft.copy(note = if (raw == CLEAR) null else raw)
                }
                break
            } catch (e: ValidationException) {
                sendTextMessage(chat, e.message.orEmpty())
            }
        }
    }
}
