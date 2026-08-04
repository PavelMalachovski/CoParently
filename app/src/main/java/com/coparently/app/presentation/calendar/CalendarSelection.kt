package com.coparently.app.presentation.calendar

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

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

    /**
     * Months the event query's anchor may drift from the displayed month before the loaded
     * window is re-centred. Paired with the window radius in [queryRangeFor]: the radius must
     * stay strictly larger, or a month reachable without a re-anchor would fall outside the
     * loaded range. `CalendarQueryRangeTest` is what defends that relationship.
     */
    const val QUERY_ANCHOR_TOLERANCE_MONTHS = 2L

    /**
     * The query anchor after the grid moved to [displayed].
     *
     * Keeps [current] while the two are within [QUERY_ANCHOR_TOLERANCE_MONTHS] of each other,
     * so ordinary month-to-month paging issues no query at all; adopts [displayed] otherwise.
     * This mirrors the hysteresis the month pager itself already has (`MonthView.anchorMonth`),
     * which the query never had — every settle re-queried, and that round-trip is what made
     * backward paging drop frames.
     */
    fun reanchor(current: YearMonth, displayed: YearMonth): YearMonth =
        if (abs(ChronoUnit.MONTHS.between(current, displayed)) > QUERY_ANCHOR_TOLERANCE_MONTHS) {
            displayed
        } else {
            current
        }
}
