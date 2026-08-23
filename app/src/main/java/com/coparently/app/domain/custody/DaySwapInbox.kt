package com.coparently.app.domain.custody

import java.time.LocalDate

/**
 * One day swap, with its date resolved.
 *
 * @property date The day being swapped.
 * @property override What was offered, and where it stands.
 */
data class DaySwap(val date: LocalDate, val override: DayOverride)

/**
 * Which swaps the inbox shows, and in what order.
 *
 * Pure, and tested as such, because it decides what a parent is shown about an agreement — the
 * same reason [DayOverrideTransition] is pure.
 *
 * **Bounded by the date, not by the status.** The stored map only ever grows: nothing deletes an
 * answered swap, because the answer is the record the parent who offered reads. Filtering on
 * "pending" instead would bound the list, but it would also mean a declined offer vanished
 * without ever being shown to the parent who made it — silent, which is the one thing this whole
 * family of features exists not to be. Dropping days that have already passed bounds it honestly
 * instead: a swap of a day already lived is history, and history is what the calendar is for.
 */
object DaySwapInbox {

    /**
     * The swaps worth showing, soonest first.
     *
     * @param overrides The pair's swaps, keyed by ISO date.
     * @param today The day to measure "already passed" against. Passed in rather than read from
     *   the clock so this stays pure and the boundary case is testable.
     * @return Swaps on [today] or later, oldest date first. A key that is not an ISO date is
     *   dropped rather than guessed at — the same rule the Firestore reader applies.
     */
    fun visible(overrides: Map<String, DayOverride>, today: LocalDate): List<DaySwap> =
        overrides.entries
            .mapNotNull { (iso, override) ->
                val date = runCatching { LocalDate.parse(iso) }.getOrNull()
                    ?: return@mapNotNull null
                DaySwap(date, override).takeIf { !date.isBefore(today) }
            }
            .sortedBy { it.date }

    /**
     * Whether [uid] is the one being asked to answer [swap] — which the parent who offered it
     * never is.
     */
    fun awaitsAnswerFrom(swap: DaySwap, uid: String): Boolean =
        swap.override.isPending && swap.override.requestedBy != uid
}
