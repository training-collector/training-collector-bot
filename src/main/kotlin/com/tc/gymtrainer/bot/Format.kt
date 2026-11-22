package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.BodyMeasurement
import com.tc.gymtrainer.bot.platform.Client
import com.tc.gymtrainer.bot.platform.ClientProfile
import com.tc.gymtrainer.bot.platform.ClientStatus
import com.tc.gymtrainer.bot.platform.WorkoutDetail
import com.tc.gymtrainer.bot.platform.WorkoutSession
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Форматирование карточек подопечных, тренировок и замеров для Telegram.
 */
object Format {
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun date(value: LocalDate): String = value.format(dateFmt)
    fun date(value: OffsetDateTime): String = value.format(dateFmt)

    /** Число без лишних нулей: 60.00 → «60», 62.50 → «62.5». */
    fun number(value: BigDecimal?): String? =
        value?.stripTrailingZeros()?.toPlainString()

    /** Короткая строка клиента для списков. */
    fun clientLine(client: Client): String {
        val archived = if (client.status == ClientStatus.ARCHIVED) " (в архиве)" else ""
        return "${client.fullName}$archived"
    }

    /** Карточка клиента. */
    fun clientCard(client: Client, weightKg: BigDecimal? = null, heightCm: BigDecimal? = null): String =
        "${profile(client.profile(), weightKg, heightCm)}\nСтатус: ${client.status.label}"

    fun profile(p: ClientProfile, weightKg: BigDecimal? = null, heightCm: BigDecimal? = null): String = buildString {
        appendLine("👤 ${p.fullName}")
        p.sex?.let { appendLine("Пол: ${it.label}") }
        p.age?.let { appendLine("Возраст: $it") }
        number(weightKg)?.let { appendLine("Вес: $it кг") }
        number(heightCm)?.let { appendLine("Рост: $it см") }
        p.telegramUsername?.let { appendLine("Telegram: @$it") }
        p.phone?.let { appendLine("Телефон: $it") }
        p.goal?.let { appendLine("Цель: $it") }
        p.notes?.let { appendLine("Заметки: $it") }
    }.trimEnd()

    /** Заголовок тренировки для списков истории. */
    fun sessionLine(session: WorkoutSession): String {
        val duration = session.durationMin?.let { ", ${it} мин" } ?: ""
        return "🗓 ${date(session.performedAt)}$duration"
    }

    /** Полная тренировка: упражнения и подходы. */
    fun workoutDetail(detail: WorkoutDetail): String = buildString {
        val s = detail.session
        appendLine("🗓 Тренировка ${date(s.performedAt)}")
        s.durationMin?.let { appendLine("⏱ Длительность: $it мин") }
        if (!s.note.isNullOrBlank()) appendLine("📝 ${s.note}")
        if (detail.exercises.isEmpty()) {
            appendLine()
            appendLine("Упражнений пока нет.")
            return@buildString
        }
        detail.exercises.forEachIndexed { i, ews ->
            appendLine()
            appendLine("${i + 1}. ${ews.exercise.exerciseName}")
            if (!ews.exercise.note.isNullOrBlank()) appendLine("   💬 ${ews.exercise.note}")
            if (ews.sets.isEmpty()) {
                appendLine("   (подходов нет)")
            } else {
                ews.sets.forEach { set -> appendLine("   • ${setLine(set.setNumber, set.weightKg, set.reps, set.restSec, set.rpe, set.durationSec)}") }
            }
        }
    }.trimEnd()

    /** Строка одного подхода: «подход 1: 60 кг × 10 (RPE 8, отдых 90с)». */
    fun setLine(
        setNumber: Int,
        weightKg: BigDecimal?,
        reps: Int?,
        restSec: Int?,
        rpe: BigDecimal?,
        durationSec: Int?,
    ): String = buildString {
        append("подход $setNumber: ")
        val result = durationSec?.let { "$it с" } ?: reps.toString()
        append(when {
            weightKg != null && weightKg.signum() != 0 -> "${number(weightKg)} кг × $result"
            durationSec != null -> result
            else -> "своим весом × $result"
        })
        val extras = buildList {
            rpe?.let { add("RPE ${number(it)}") }
            restSec?.let { add("отдых ${it}с") }
        }
        if (extras.isNotEmpty()) append(" (${extras.joinToString(", ")})")
    }

    /** Карточка замера. */
    fun measurementCard(m: BodyMeasurement): String = buildString {
        appendLine("📏 Замер ${date(m.measuredOn)}")
        number(m.heightCm)?.let { appendLine("Рост: $it см") }
        number(m.backWidthCm)?.let { appendLine("Ширина спины: $it см") }
        number(m.calfCm)?.let { appendLine("Обхват голени без указания стороны: $it см") }
        number(m.weightKg)?.let { appendLine("Вес: $it кг") }
        number(m.bodyFatPct)?.let { appendLine("% жира: $it") }
        number(m.chestCm)?.let { appendLine("Обхват груди: $it см") }
        number(m.waistCm)?.let { appendLine("Обхват талии: $it см") }
        number(m.hipsCm)?.let { appendLine("Обхват ягодиц: $it см") }
        number(m.bicepsCm)?.let { appendLine("Обхват руки без указания стороны: $it см") }
        number(m.thighCm)?.let { appendLine("Обхват бедра без указания стороны: $it см") }
        number(m.rightBicepsCm)?.let { appendLine("Обхват правой руки: $it см") }
        number(m.leftBicepsCm)?.let { appendLine("Обхват левой руки: $it см") }
        number(m.rightForearmCm)?.let { appendLine("Обхват правого предплечья: $it см") }
        number(m.leftForearmCm)?.let { appendLine("Обхват левого предплечья: $it см") }
        number(m.rightThighCm)?.let { appendLine("Обхват правого бедра: $it см") }
        number(m.leftThighCm)?.let { appendLine("Обхват левого бедра: $it см") }
        number(m.rightCalfCm)?.let { appendLine("Обхват правой голени: $it см") }
        number(m.leftCalfCm)?.let { appendLine("Обхват левой голени: $it см") }
        if (!m.note.isNullOrBlank()) appendLine("Заметка: ${m.note}")
    }.trimEnd()
}
