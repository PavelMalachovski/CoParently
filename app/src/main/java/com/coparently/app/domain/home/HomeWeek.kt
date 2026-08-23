package com.coparently.app.domain.home

import com.coparently.app.domain.model.Event
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One row of the child's week on the home screen.
 *
 * @property event The event itself.
 * @property dayParent The slot — `"mom"` or `"dad"` — whose custody day the event falls on, or
 *   null when no arrangement answers for that date. It is deliberately *not* [Event.parentOwner]:
 *   the row's question is "whose day does this fall on", which is what a separated parent needs
 *   in order to know who is taking the child, and an event's owner is a different fact that the
 *   event's own screen already shows.
 * @property key A stable list key. Recurring occurrences share the master event's id, so the id
 *   alone collides — two occurrences of the same series in one week would be one row, and which
 *   one survived would be up to the list.
 */
data class WeekEntry(
    val event: Event,
    val dayParent: String?,
    val key: String
)

/**
 * The child's week, as the home screen shows it.
 *
 * Pure, so the window arithmetic and the filtering can be tested without a ViewModel, a
 * repository or a clock.
 */
object HomeWeek {

    /**
     * How far ahead the week reaches.
     *
     * Seven days **from today**, not Monday-to-Sunday. A parent opening the app on Friday wants
     * the weekend; a calendar week would give them two days and a wrap-up.
     */
    const val DAYS = 7L

    /**
     * The week's rows, earliest first.
     *
     * @param events Events overlapping the window, as the repository returns them — recurring
     *   series already expanded into occurrences.
     * @param now The moment the screen is being drawn. An event that has already started today
     *   is behind the parent, not ahead of them, so the window opens here rather than at
     *   midnight; it closes at the start of the eighth day, so the seventh day is whole.
     * @param userId This device's Firebase UID, or blank while the session is unresolved.
     * @param custodyFor Whose day a date is. Bind it through
     *   [com.coparently.app.domain.custody.CustodyResolver] so this cannot disagree with the
     *   grid about the same date.
     */
    fun of(
        events: List<Event>,
        now: LocalDateTime,
        userId: String,
        custodyFor: (LocalDate) -> String?
    ): List<WeekEntry> {
        val end = now.toLocalDate().plusDays(DAYS).atStartOfDay()
        return events
            .filter { it.startDateTime >= now && it.startDateTime < end }
            .filter { it.isVisibleTo(userId) }
            .sortedBy { it.startDateTime }
            .map { event ->
                WeekEntry(
                    event = event,
                    dayParent = custodyFor(event.startDateTime.toLocalDate()),
                    key = "${event.id}@${event.startDateTime}"
                )
            }
    }

    /**
     * Whether [userId] may see this event.
     *
     * A private event is its creator's alone. It never reaches Firestore, so the co-parent's
     * should never be on this device in the first place — this is the belt to that braces, and
     * it costs one comparison on a list of at most a week's events.
     *
     * An event with no creator stamped is treated as this user's. Nothing distinguishes their own
     * un-stamped row from anybody else's, every such row is on their own device, and hiding it
     * would remove a parent's own private event from their own screen.
     */
    private fun Event.isVisibleTo(userId: String): Boolean =
        !isPrivate || createdByFirebaseUid.isNullOrBlank() || createdByFirebaseUid == userId
}
