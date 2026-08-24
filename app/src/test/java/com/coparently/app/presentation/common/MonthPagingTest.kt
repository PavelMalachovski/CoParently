package com.coparently.app.presentation.common

import org.junit.Test
import java.time.YearMonth
import kotlin.test.assertEquals

/**
 * Which way a month change travels on screen.
 *
 * The direction is the one part of a slide transition that can be *wrong* rather than merely
 * ugly: a month arriving from the wrong edge reads as having gone back when it went forward, and
 * nothing about the animation itself would look broken. Everything else in the transition —
 * duration, easing, the fade — is a matter of taste and is not tested.
 */
class MonthPagingTest {

    private val august = YearMonth.of(2026, 8)

    @Test
    fun `a later month arrives from the end edge`() {
        assertEquals(1, monthPagingDirection(august, august.plusMonths(1)))
    }

    @Test
    fun `an earlier month arrives from the start edge`() {
        assertEquals(-1, monthPagingDirection(august, august.minusMonths(1)))
    }

    @Test
    fun `the same month does not move`() {
        // Not hypothetical: the expenses content also changes when a row is added, deleted or
        // restored by Undo, and a slide there would announce a page turn that never happened.
        assertEquals(0, monthPagingDirection(august, august))
    }

    @Test
    fun `December to January still reads as forwards`() {
        // The trap a naive comparison of month-of-year falls into: 1 is less than 12, so
        // January would arrive from the wrong edge every new year.
        val december = YearMonth.of(2026, 12)
        assertEquals(1, monthPagingDirection(december, YearMonth.of(2027, 1)))
        assertEquals(-1, monthPagingDirection(YearMonth.of(2027, 1), december))
    }

    @Test
    fun `a jump of many months still travels one screen`() {
        // `YearMonth.compareTo` returns the raw difference, which as an offset multiplier would
        // start the incoming content whole screens away and slide it in from nowhere.
        assertEquals(1, monthPagingDirection(august, august.plusYears(3)))
        assertEquals(-1, monthPagingDirection(august, august.minusYears(3)))
    }
}
