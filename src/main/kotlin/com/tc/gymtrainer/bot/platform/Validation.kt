package com.tc.gymtrainer.bot.platform

import java.math.BigDecimal

internal fun BigDecimal.fitsDecimal(precision: Int, scale: Int): Boolean {
    val value = stripTrailingZeros()
    return value.scale() <= scale && value.abs() < BigDecimal.TEN.pow(precision - scale)
}

fun validateMeasurement(input: MeasurementInput) {
    val lengths = listOf(
        input.heightCm, input.chestCm, input.waistCm, input.hipsCm, input.bicepsCm, input.thighCm, input.calfCm,
        input.rightBicepsCm, input.leftBicepsCm, input.rightForearmCm, input.leftForearmCm,
        input.rightThighCm, input.leftThighCm, input.rightCalfCm, input.leftCalfCm,
        input.backWidthCm,
    ).filterNotNull()
    val hasAnyValue = lengths.isNotEmpty() || input.weightKg != null || input.bodyFatPct != null || !input.note.isNullOrBlank()
    if (!hasAnyValue) throw ValidationException("Замер пустой: укажите хотя бы один показатель.")
    input.weightKg?.let {
        if (it <= BigDecimal.ZERO || !it.fitsDecimal(5, 2)) throw ValidationException("Недопустимый вес (кг).")
    }
    if (lengths.any { it <= BigDecimal.ZERO || !it.fitsDecimal(5, 1) }) {
        throw ValidationException("Рост и обхваты должны быть положительными, с точностью до 0,1 см.")
    }
    input.bodyFatPct?.let {
        if (it < BigDecimal.ZERO || it > BigDecimal("100") || !it.fitsDecimal(4, 1)) throw ValidationException("% жира должен быть в диапазоне 0–100.")
    }
}

fun validateSet(set: SetDraft) {
    if ((set.reps == null) == (set.durationSec == null)) {
        throw ValidationException("Укажите один результат подхода: повторы или длительность в секундах.")
    }
    if (set.durationSec != null && set.durationSec <= 0) throw ValidationException("Длительность подхода должна быть положительной.")
    if (set.reps != null && set.reps <= 0) throw ValidationException("Количество повторений должно быть положительным.")
    set.weightKg?.let { if (it < BigDecimal.ZERO || !it.fitsDecimal(6, 2)) throw ValidationException("Недопустимый вес подхода (кг).") }
    set.restSec?.let { if (it < 0) throw ValidationException("Отдых не может быть отрицательным.") }
    set.rpe?.let {
        if (it < BigDecimal.ZERO || it > BigDecimal("10") || !it.fitsDecimal(3, 1)) throw ValidationException("RPE должен быть в диапазоне 0–10.")
    }
}

private val TELEGRAM_USERNAME = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")

fun validateProfile(profile: ClientProfile): ClientProfile {
    val clean = profile.copy(firstName = profile.firstName.trim(), lastName = profile.lastName?.trim()?.ifEmpty { null },
        telegramUsername = profile.telegramUsername?.trim()?.ifEmpty { null }?.removePrefix("@"),
        notes = profile.notes?.trim()?.ifEmpty { null })
    if (clean.firstName.isEmpty() || clean.fullName.length > 255) throw ValidationException("Имя и фамилия вместе должны занимать от 1 до 255 символов.")
    if (clean.age != null && clean.age !in 0..130) throw ValidationException("Введите возраст в полных годах от 0 до 130.")
    if (clean.telegramUsername != null && !TELEGRAM_USERNAME.matches(clean.telegramUsername)) {
        throw ValidationException("Введите ник Telegram латиницей, например @username.")
    }
    return clean
}
