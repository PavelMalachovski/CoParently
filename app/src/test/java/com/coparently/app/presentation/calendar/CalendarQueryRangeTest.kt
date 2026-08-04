package com.coparently.app.presentation.calendar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The MONTH query window is anchored and sticky, so it must cover the grid of every month the
 * user can page to without re-anchoring. If it does not, paging shows a month whose events were
 * never loaded — an empty grid that looks like data loss.
 */
class CalendarQueryRangeTest {

    /**
     * The 42 cells kizitonwose renders for [month] under `OutDateStyle.EndOfGrid`: six rows
     * starting from the Monday on or before the 1st.
     */
    private fun gridBounds(month: YearMonth): Pair<LocalDate, LocalDate> {
        var start = month.atDay(1)
        while (start.dayOfWeek != DayOfWeek.MONDAY) {
            start = start.minusDays(1)
        }
        return start to start.plusDays(41)
    }

    private fun windowFor(anchor: YearMonth): Pair<LocalDate, LocalDate> {
        val (start, end) = queryRangeFor(CalendarViewMode.MONTH, anchor.atDay(1))
        return start.toLocalDate() to end.toLocalDate()
    }

    @Test
    fun `the window covers every grid reachable without a re-anchor`() {
        val anchors = listOf(
            YearMonth.of(2026, 1),
            YearMonth.of(2026, 2), // short month
            YearMonth.of(2026, 8),
            YearMonth.of(2026, 11), // window crosses the year boundary
            YearMonth.of(2027, 1),
            YearMonth.of(2028, 2) // leap February
        )
        val tolerance = CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS

        for (anchor in anchors) {
            val (windowStart, windowEnd) = windowFor(anchor)
            for (offset in -tolerance..tolerance) {
                val displayed = anchor.plusMonths(offset)
                val (gridStart, gridEnd) = gridBounds(displayed)
                assertTrue(
                    "grid of $displayed starts before the window anchored on $anchor",
                    !gridStart.isBefore(windowStart)
                )
                assertTrue(
                    "grid of $displayed ends after the window anchored on $anchor",
                    !gridEnd.isAfter(windowEnd)
                )
            }
        }
    }

    @Test
    fun `the window radius leaves room beyond the re-anchor tolerance`() {
        assertTrue(
            "the window must reach further than the anchor is allowed to drift",
            MONTH_WINDOW_RADIUS > CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS
        )
    }

    @Test
    fun `the window is week-aligned so no partial row is unloaded`() {
        val (start, end) = queryRangeFor(CalendarViewMode.MONTH, YearMonth.of(2026, 8).atDay(1))
        assertTrue("window starts on a Monday", start.dayOfWeek == DayOfWeek.MONDAY)
        assertTrue("window ends on a Sunday", end.dayOfWeek == DayOfWeek.SUNDAY)
    }
}
