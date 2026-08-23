package com.coparently.app.domain.events

import com.coparently.app.domain.model.Event

/**
 * Which events a calendar view may draw.
 *
 * One rule, in one place, applied at the one query every view reads from. `CalendarScreen`'s
 * month grid, the week view and the day view all draw from
 * `EventRepository.getEventsByDateRange`, so filtering there — and only there — is what makes a
 * single rule cover every view rather than three that can disagree. CLAUDE.md already requires
 * range queries to go through that function for exactly this reason.
 *
 * An event waiting on the other parent, or turned down by them, is not drawn. A grid that shows
 * unagreed events is the problem this package exists to remove.
 */
object CalendarVisibility {

    /**
     * [events] minus the ones no calendar view may show.
     *
     * **Apply this to recurring masters *before* expansion, not to the occurrences after it.**
     * Occurrences share their master's id and are generated at query time by `RecurrenceExpander`;
     * there is no per-occurrence row to carry a status, and accepting "football every Tuesday"
     * means accepting the series. Filtering afterwards would work by accident today — the
     * expander copies the master's fields — and would stop working the moment an occurrence
     * carried anything of its own.
     */
    fun visible(events: List<Event>): List<Event> =
        events.filterNot { it.acceptance.hidesFromCalendar }
}
