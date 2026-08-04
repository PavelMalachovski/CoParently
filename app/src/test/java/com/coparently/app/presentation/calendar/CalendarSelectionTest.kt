package com.coparently.app.presentation.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarSelectionTest {

    private val today = LocalDate.of(2026, 8, 3)

    @Test
    fun `the current month selects today`() {
        assertEquals(today, CalendarSelection.forMonth(YearMonth.of(2026, 8), today))
    }

    @Test
    fun `a future month selects nothing`() {
        assertNull(CalendarSelection.forMonth(YearMonth.of(2026, 9), today))
    }

    @Test
    fun `a past month selects nothing`() {
        assertNull(CalendarSelection.forMonth(YearMonth.of(2026, 3), today))
    }

    @Test
    fun `the same month number in another year is not this month`() {
        assertNull(CalendarSelection.forMonth(YearMonth.of(2025, 8), today))
        assertNull(CalendarSelection.forMonth(YearMonth.of(2027, 8), today))
    }

    @Test
    fun `month view anchors on the displayed month regardless of selection`() {
        assertEquals(
            LocalDate.of(2026, 12, 1),
            CalendarSelection.anchorDate(
                viewMode = CalendarViewMode.MONTH,
                month = YearMonth.of(2026, 12),
                selectedDate = null,
                today = today
            )
        )
    }

    @Test
    fun `day view anchors on the selected day`() {
        assertEquals(
            LocalDate.of(2026, 9, 14),
            CalendarSelection.anchorDate(
                viewMode = CalendarViewMode.DAY,
                month = YearMonth.of(2026, 9),
                selectedDate = LocalDate.of(2026, 9, 14),
                today = today
            )
        )
    }

    @Test
    fun `day view falls back to today when nothing is selected`() {
        assertEquals(
            today,
            CalendarSelection.anchorDate(
                viewMode = CalendarViewMode.WEEK,
                month = YearMonth.of(2026, 8),
                selectedDate = null,
                today = today
            )
        )
    }

    @Test
    fun `the anchor holds while the displayed month stays within tolerance`() {
        val anchor = YearMonth.of(2026, 8)
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 8)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 9)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 10)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 7)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 6)))
    }

    @Test
    fun `the anchor moves to the displayed month once tolerance is exceeded`() {
        val anchor = YearMonth.of(2026, 8)
        assertEquals(YearMonth.of(2026, 11), CalendarSelection.reanchor(anchor, YearMonth.of(2026, 11)))
        assertEquals(YearMonth.of(2026, 5), CalendarSelection.reanchor(anchor, YearMonth.of(2026, 5)))
    }

    @Test
    fun `distance is measured in months and not in month numbers`() {
        // December 2026 -> January 2027 is one month apart. Subtracting month numbers
        // makes it eleven, which would re-anchor on every year boundary.
        val anchor = YearMonth.of(2026, 12)
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2027, 1)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2027, 2)))
        assertEquals(YearMonth.of(2027, 3), CalendarSelection.reanchor(anchor, YearMonth.of(2027, 3)))
    }

    @Test
    fun `a far jump such as the Today pill re-anchors`() {
        assertEquals(
            YearMonth.of(2026, 8),
            CalendarSelection.reanchor(YearMonth.of(2019, 4), YearMonth.of(2026, 8))
        )
    }
}
