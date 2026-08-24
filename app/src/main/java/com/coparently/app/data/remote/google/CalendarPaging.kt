package com.coparently.app.data.remote.google

import com.google.api.services.calendar.model.Event as GoogleCalendarEvent
import java.time.LocalDateTime

/**
 * What one import of a Google calendar actually took, and the two bounds it stopped at.
 *
 * Both bounds are carried rather than left implicit, because the defect this type exists to close
 * was not the truncation itself — a cap is reasonable — but that a truncated import was
 * indistinguishable from a complete one. A caller that cannot say "and there is more" will tell
 * the user the import finished.
 *
 * @property events The events fetched, in start order.
 * @property truncated True when the cap stopped the fetch and the calendar holds more inside the
 *   window. False means the window was read to the end.
 * @property from Start of the window actually queried.
 * @property until End of the window actually queried — supplied by the caller, or a default
 *   horizon, but never absent.
 */
data class CalendarEvents(
    val events: List<GoogleCalendarEvent>,
    val truncated: Boolean,
    val from: LocalDateTime,
    val until: LocalDateTime
)

/** One page as the Google client returns it: the items, and the token for the next page if any. */
internal data class PageOf<T>(val items: List<T>, val nextPageToken: String?)

/** Everything [collectPages] gathered, and whether a limit cut it short. */
internal data class Paged<T>(val items: List<T>, val truncated: Boolean)

/**
 * Follows [fetch]'s page tokens until the pages run out, [limit] items are held, or [maxPages]
 * requests have been made.
 *
 * Separate from [GoogleCalendarApi] on purpose: paging is where the truncation bug lived, and
 * every interesting case (a limit landing mid-page, a last page with no token, a server that
 * keeps handing back tokens) is testable here without a Google client, a credential or a network.
 *
 * [maxPages] is not a product limit — it is the guard that stops a server returning a
 * non-advancing token from hanging an import. Hitting it reports [Paged.truncated], the same as
 * hitting [limit], because in both cases the caller holds less than the window contains.
 */
internal fun <T> collectPages(
    limit: Int,
    maxPages: Int,
    fetch: (pageToken: String?) -> PageOf<T>
): Paged<T> {
    val collected = mutableListOf<T>()
    var pageToken: String? = null

    repeat(maxPages) {
        val page = fetch(pageToken)
        val room = limit - collected.size
        if (page.items.size > room) {
            collected += page.items.take(room.coerceAtLeast(0))
            return Paged(collected, truncated = true)
        }

        collected += page.items
        pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }
            ?: return Paged(collected, truncated = false)

        // A token exists but there is no room left for what it points at.
        if (collected.size >= limit) return Paged(collected, truncated = true)
    }

    return Paged(collected, truncated = true)
}
