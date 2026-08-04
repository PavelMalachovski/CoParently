package com.coparently.app.presentation.calendar

import java.time.LocalDate
import java.time.YearMonth

/**
 * Rules tying the month the grid shows to the day the user has chosen.
 *
 * These were the same value until August 2026, which is why paging a month silently "selected"
 * its 1st and the agenda card underneath announced that nothing was scheduled on a day nobody
 * had picked.
 */
object CalendarSelection {

    /**
     * The selection a freshly displayed [month] should carry.
     *
     * @return Today when [month] is today's month — arriving on the calendar should answer
     *   "what is on today" — and null otherwise, because no day in another month has been chosen.
     */
    fun forMonth(month: YearMonth, today: LocalDate): LocalDate? =
        today.takeIf { YearMonth.from(it) == month }

    /**
     * The date the event query range is computed from.
     *
     * In MONTH mode the grid is the unit of work, so the range follows the displayed month and
     * not the selection, which may be absent. Day and Week need a concrete day and fall back to
     * today when nothing is selected.
     */
    fun anchorDate(
        viewMode: CalendarViewMode,
        displayedMonth: YearMonth,
        selectedDate: LocalDate?,
        today: LocalDate
    ): LocalDate = when (viewMode) {
        CalendarViewMode.MONTH -> displayedMonth.atDay(1)
        CalendarViewMode.WEEK, CalendarViewMode.DAY -> selectedDate ?: today
    }
}
