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
                displayedMonth = YearMonth.of(2026, 12),
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
                displayedMonth = YearMonth.of(2026, 9),
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
                displayedMonth = YearMonth.of(2026, 8),
                selectedDate = null,
                today = today
            )
        )
    }
}
