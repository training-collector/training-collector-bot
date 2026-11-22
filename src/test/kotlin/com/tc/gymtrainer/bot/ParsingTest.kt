package com.tc.gymtrainer.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ParsingTest {

    @Test
    fun `parses weight x reps`() {
        val set = Parsing.setOrNull("60x10")!!
        assertEquals(BigDecimal("60"), set.weightKg)
        assertEquals(10, set.reps)
    }

    @Test
    fun `parses space separated and comma decimal`() {
        val set = Parsing.setOrNull("62,5 8")!!
        assertEquals(BigDecimal("62.5"), set.weightKg)
        assertEquals(8, set.reps)
    }

    @Test
    fun `parses cyrillic separator and rpe and rest`() {
        val set = Parsing.setOrNull("80х6 @8.5 /120")!!
        assertEquals(BigDecimal("80"), set.weightKg)
        assertEquals(6, set.reps)
        assertEquals(BigDecimal("8.5"), set.rpe)
        assertEquals(120, set.restSec)
    }

    @Test
    fun `parses bodyweight variants`() {
        assertNull(Parsing.setOrNull("x12")!!.weightKg)
        assertEquals(12, Parsing.setOrNull("x12")!!.reps)
        assertNull(Parsing.setOrNull("свой 15")!!.weightKg)
        assertEquals(15, Parsing.setOrNull("свой 15")!!.reps)
    }

    @Test
    fun `rejects garbage and non-positive reps`() {
        assertNull(Parsing.setOrNull("abc"))
        assertNull(Parsing.setOrNull("60x0"))
        assertNull(Parsing.setOrNull(""))
    }

    @Test
    fun `parses timed sets with optional weight and rest`() {
        val timed = Parsing.setOrNull("5x30с @8 /90")!!
        assertNull(timed.reps)
        assertEquals(30, timed.durationSec)
        assertEquals(BigDecimal("5"), timed.weightKg)
        assertEquals(90, timed.restSec)
        assertEquals(45, Parsing.setOrNull("45s")!!.durationSec)
        assertNull(Parsing.setOrNull("0с"))
    }

    @Test
    fun `validates the entire set and accepts seconds after a space`() {
        for (raw in listOf("60 10 extra", "60 10 12", "60x10x4", "-2 8", "99999 8", "5 0с", "50 8 @11", "10 3 /99999999999")) {
            assertNull(Parsing.setOrNull(raw), raw)
        }
        assertEquals(30, Parsing.setOrNull("5 30с")?.durationSec)
        assertEquals(12, Parsing.setOrNull("0 12")?.reps)
    }

    @Test
    fun `parses dates`() {
        val today = LocalDate.of(2026, 7, 12)
        assertEquals(today, Parsing.dateOrNull("сегодня", today))
        assertEquals(today.minusDays(1), Parsing.dateOrNull("вчера", today))
        assertEquals(LocalDate.of(2026, 3, 1), Parsing.dateOrNull("01.03.2026", today))
        assertEquals(LocalDate.of(2026, 3, 1), Parsing.dateOrNull("1.3", today))
        assertEquals(LocalDate.of(2024, 12, 31), Parsing.dateOrNull("31.12.24", today))
        assertNull(Parsing.dateOrNull("не дата", today))
    }
}
