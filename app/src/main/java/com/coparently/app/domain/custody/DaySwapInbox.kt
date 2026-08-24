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

    /**
     * The same swaps, folded into the offers they were actually made as.
     *
     * A run of days offered together is one agreement, and until it was grouped the co-parent
     * got one inbox card, one modal dialog and one push **per day** — five of each for a school
     * holiday week, each asking the same question. Grouping is done here rather than in storage
     * for the reason [DayOverride.groupId] gives: `firestore.rules` can only validate a write
     * that names one date.
     *
     * An entry with no group id is its own group, which is what every swap written before groups
     * existed is, and what a single-day swap still is.
     *
     * @param overrides The pair's swaps, keyed by ISO date.
     * @param today The day to measure "already passed" against.
     * @return Groups ordered by their first day, each group's days ordered within it.
     */
    fun groups(overrides: Map<String, DayOverride>, today: LocalDate): List<DaySwapGroup> {
        val visible = visible(overrides, today)
        // A null id must not collapse every ungrouped swap into one card, so each such day keys
        // on its own date instead.
        return visible
            .groupBy { it.override.groupId ?: "single:${it.date}" }
            .values
            .map { DaySwapGroup(it.sortedBy { swap -> swap.date }) }
            .sortedBy { it.firstDate }
    }
}

/**
 * One offer, however many days it covers.
 *
 * @property swaps The days of the offer, soonest first. Never empty.
 */
data class DaySwapGroup(val swaps: List<DaySwap>) {

    /** The offer's first day, which is what the inbox orders on. */
    val firstDate: LocalDate get() = swaps.first().date

    /** The offer's last day. Equal to [firstDate] for a single-day swap. */
    val lastDate: LocalDate get() = swaps.last().date

    /** How many days the offer covers. */
    val dayCount: Int get() = swaps.size

    /**
     * A stable key for this group, for a `LazyColumn` and for the "put it off" set on Home.
     *
     * The group id when there is one, else the single date — so a dismissed card does not come
     * back under a new key on the next recomposition.
     */
    val key: String get() = swaps.first().override.groupId ?: "single:${firstDate}"

    /** The ISO dates the offer covers, which is what a group answer is applied to. */
    val dates: List<String> get() = swaps.map { it.date.toString() }

    /** Whether [uid] is the one being asked. True when any day of the offer is waiting on them. */
    fun awaitsAnswerFrom(uid: String): Boolean =
        swaps.any { DaySwapInbox.awaitsAnswerFrom(it, uid) }
}
