package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.SetDraft
import com.tc.gymtrainer.bot.platform.ValidationException
import com.tc.gymtrainer.bot.platform.validateSet
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Разбор пользовательского ввода в быстром вводе тренировок и замеров.
 * Формат подхода нацелен на скорость: «вес повторения», опц. «@RPE» и «/отдых».
 */
object Parsing {

    /** Число с точкой или запятой; «-» / пусто → null. */
    fun decimalOrNull(raw: String): BigDecimal? {
        val s = raw.trim().replace(',', '.')
        if (s.isEmpty() || s == "-") return null
        return s.toBigDecimalOrNull()
    }

    /**
     * Дата: «дд.мм.гггг», «дд.мм» (текущий год), «сегодня», «вчера».
     * [today] задаётся снаружи для тестируемости.
     */
    fun dateOrNull(raw: String, today: LocalDate): LocalDate? {
        val s = raw.trim().lowercase()
        return when (s) {
            "", "-", "сегодня", "today" -> today
            "вчера" -> today.minusDays(1)
            else -> {
                val parts = s.split('.', '/', '-').map { it.trim().toIntOrNull() ?: return null }
                runCatching {
                    when (parts.size) {
                        2 -> LocalDate.of(today.year, parts[1], parts[0])
                        3 -> {
                            val year = if (parts[2] < 100) 2000 + parts[2] else parts[2]
                            LocalDate.of(year, parts[1], parts[0])
                        }
                        else -> null
                    }
                }.getOrNull()
            }
        }
    }

    private val weightedSet = Regex("""(\d+(?:[.,]\d+)?|свой|св|bw|-)(?:\s*[xх*]\s*|\s+)(\d+)\s*([сs]?)""")
    private val bodyweightSet = Regex("""[xх*]\s*(\d+)""")
    private val timedSet = Regex("""(\d+)\s*[сs]""")
    private val suffix = Regex("""\s*([@/])\s*(\d+(?:[.,]\d+)?)$""")

    fun setOrNull(raw: String): SetDraft? {
        var line = raw.trim().lowercase()
        var rest: Int? = null
        var rpe: BigDecimal? = null
        while (true) {
            val option = suffix.find(line) ?: break
            val value = option.groupValues[2]
            if (option.groupValues[1] == "/") {
                if (rest != null) return null
                rest = value.toIntOrNull() ?: return null
            } else {
                if (rpe != null) return null
                rpe = decimalOrNull(value) ?: return null
            }
            line = line.substring(0, option.range.first).trimEnd()
        }
        val weighted = weightedSet.matchEntire(line)
        val timed = timedSet.matchEntire(line)
        val metric = weighted?.groupValues?.get(2) ?: timed?.groupValues?.get(1)
            ?: bodyweightSet.matchEntire(line)?.groupValues?.get(1) ?: return null
        val count = metric.toIntOrNull() ?: return null
        val seconds = timed != null || weighted?.groupValues?.get(3)?.isNotEmpty() == true
        val weight = weighted?.groupValues?.get(1)?.let(::decimalOrNull)
        val set = SetDraft(weight, if (seconds) null else count, rest, rpe, if (seconds) count else null)
        return try {
            validateSet(set)
            set
        } catch (_: ValidationException) {
            null
        }
    }
}
