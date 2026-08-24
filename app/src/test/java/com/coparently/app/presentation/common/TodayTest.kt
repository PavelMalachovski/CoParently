package com.coparently.app.presentation.common

import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wait behind [rememberToday].
 *
 * The composable itself needs a Compose host to exercise, but the part that can be arithmetically
 * wrong does not: how long to sleep before asking the clock again.
 */
class TodayTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun `a whole day is owed at the stroke of midnight`() {
        val atMidnight = LocalDateTime.of(2026, 8, 24, 0, 0, 0)

        assertEquals(day, millisUntilNextMidnight(atMidnight))
    }

    @Test
    fun `an hour is owed at eleven at night`() {
        val lateEvening = LocalDateTime.of(2026, 8, 24, 23, 0, 0)

        assertEquals(hour, millisUntilNextMidnight(lateEvening))
    }

    @Test
    fun `a second before midnight is a second, not a day`() {
        val almostMidnight = LocalDateTime.of(2026, 8, 24, 23, 59, 59)

        assertEquals(1_000L, millisUntilNextMidnight(almostMidnight))
    }

    @Test
    fun `the wait is never zero, so the loop cannot spin against the clock`() {
        // A timer firing a hair early lands here. Waiting "no time at all" would make a date that
        // updates itself cost more than one that never does.
        val aMillisecondBeforeMidnight = LocalDateTime.of(2026, 8, 24, 23, 59, 59, 999_000_000)

        assertTrue(millisUntilNextMidnight(aMillisecondBeforeMidnight) > 0)
    }

    @Test
    fun `the month and year roll over like any other day`() {
        assertEquals(day, millisUntilNextMidnight(LocalDateTime.of(2026, 12, 31, 0, 0)))
        assertEquals(hour, millisUntilNextMidnight(LocalDateTime.of(2026, 2, 28, 23, 0)))
    }
}
