package com.coparently.app.domain.events

/**
 * Whether an event still needs the other parent's word before it counts.
 *
 * Deliberately **not** `Event.pickupConfirmedBy`, which is live and means something else: that
 * field records a parent confirming they *collected the child*, after the fact, and `DayWeekView`
 * already renders it. Consent before the event exists at all is a different question, and folding
 * the two into one column would break the display that depends on the old meaning.
 */
enum class EventAcceptance {
    /**
     * Nobody has to agree to this event.
     *
     * The default, and it carries the whole backward story: every event that exists today, every
     * event a parent creates for themselves, every event created while unpaired, and every
     * private event. That default is what makes the Room migration additive and what stops this
     * change rewriting history.
     */
    NOT_REQUIRED,

    /** Created for the co-parent and waiting on them. Absent from every calendar view. */
    PENDING,

    /** Agreed. From here the event behaves like any other. */
    ACCEPTED,

    /**
     * Turned down. Still absent from every calendar view, and deliberately **not** deleted: the
     * creator needs to know the answer was no, and an event that silently vanished would read as
     * a bug in an app whose whole premise is that changes are never silent.
     */
    DECLINED;

    /** True while this event should be kept out of the calendar. */
    val hidesFromCalendar: Boolean get() = this == PENDING || this == DECLINED

    /** True while an answer is still outstanding. */
    val isPending: Boolean get() = this == PENDING
}
