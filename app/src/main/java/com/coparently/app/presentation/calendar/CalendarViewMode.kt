package com.coparently.app.presentation.calendar

/**
 * Calendar view modes, ordered by roadmap priority: month first, week next, day third.
 */
enum class CalendarViewMode {
    MONTH,
    WEEK,
    DAY
}

/**
 * Whose events are visible in the calendar.
 *
 * BOTH shows the mutual view with both parents' events at the same time. FRIEND is not a fourth
 * owner — a calendar friend never owns a day (item 16) — it narrows to the events a friend
 * actually takes part in, which is the only question their presence raises.
 */
enum class ParentFilter {
    BOTH,
    MOM,
    DAD,
    FRIEND
}
