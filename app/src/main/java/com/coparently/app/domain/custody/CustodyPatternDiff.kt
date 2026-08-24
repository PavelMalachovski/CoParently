package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import java.time.LocalDate

/** How many days ahead a diff looks when it cannot use the two cycles' least common multiple. */
private const val DEFAULT_WINDOW_DAYS = 28

/** Refuses a cycle longer than a year, the same bound `CustodyModel.isEquivalentTo` applies. */
private const val MAX_SANE_PATTERN_DAYS = 366

/**
 * One date whose custody the proposal would move.
 *
 * @property date The day.
 * @property fromSlot The slot that has it today — `"mom"` or `"dad"`, the schema's two slot
 *   identifiers, never a name. The screen resolves them through `ParentNames`.
 * @property toSlot The slot that would have it.
 */
data class MovedDay(val date: LocalDate, val fromSlot: String, val toSlot: String)

/**
 * What a proposed custody pattern would actually change, as data.
 *
 * The co-parent's popup used to say only that a new schedule had been proposed and who by. The
 * information to say more was already on the same document — `SharedCustody` carries the agreed
 * `model` and the pending `proposal.model` side by side — and every consumer threw the agreed
 * half away one line before the UI. This is the missing piece: a value object the screen turns
 * into a sentence.
 *
 * No strings and no `Context`: slots and dates only, like [DayOverride] and `SwapError`, so the
 * five locales resolve at the composable and the whole thing stays unit-testable.
 *
 * @property movedDays Days inside the window whose slot changes, soonest first.
 * @property netDaysBySlot Net day change per slot over the window — `{"mom": -3, "dad": +3}`.
 *   A slot with no change is absent.
 * @property windowDays How many days from the anchor were compared, so the screen can say what
 *   "3 days move" is measured over.
 * @property identical True when the two patterns assign every day the same way; a proposal that
 *   changes nothing visible is worth saying out loud rather than describing as "0 days".
 * @property comparable False when one of the patterns could not be read — an unvalidated cycle
 *   length off the co-parent's document. The screen then falls back to the old wording rather
 *   than printing a confidently wrong "nothing changes".
 */
data class CustodyPatternDiff(
    val movedDays: List<MovedDay>,
    val netDaysBySlot: Map<String, Int>,
    val windowDays: Int,
    val identical: Boolean,
    val comparable: Boolean
) {
    /** How many days move — the number the summary sentence leads with. */
    val movedDayCount: Int get() = movedDays.size

    companion object {

        /**
         * Compares [proposed] against [agreed] over a bounded window.
         *
         * **Anchored on [from], not on either pattern's own start date.** Two previews are only
         * comparable when they cover the same dates, which is the rule `FortnightPreview` already
         * chose; anchoring on the proposal's start would also describe a fortnight the parent
         * cannot see on their calendar today.
         *
         * The window is the least common multiple of the two cycles when that is small enough to
         * scan, and [DEFAULT_WINDOW_DAYS] otherwise: a shorter window can make a 14-day and a
         * 21-day pattern look identical, and an unbounded one can be pushed past `Int.MAX_VALUE`
         * by an unvalidated `patternDays` — where a negative window makes `(0 until window)`
         * empty and every comparison silently answer "nothing changed".
         *
         * @param agreed The pattern in force, or null when the pair has none yet.
         * @param proposed The pattern being offered.
         * @param from First day to compare, normally today.
         * @param maxWindowDays Ceiling on the scan.
         */
        fun of(
            agreed: CustodyModel?,
            proposed: CustodyModel,
            from: LocalDate,
            maxWindowDays: Int = DEFAULT_WINDOW_DAYS * 2
        ): CustodyPatternDiff {
            if (agreed == null ||
                agreed.patternDays !in 1..MAX_SANE_PATTERN_DAYS ||
                proposed.patternDays !in 1..MAX_SANE_PATTERN_DAYS
            ) {
                return CustodyPatternDiff(
                    movedDays = emptyList(),
                    netDaysBySlot = emptyMap(),
                    windowDays = 0,
                    identical = false,
                    comparable = false
                )
            }

            val window = windowFor(agreed.patternDays, proposed.patternDays, maxWindowDays)
            val moved = (0 until window).mapNotNull { offset ->
                val date = from.plusDays(offset.toLong())
                val before = agreed.getCustodyFor(date)
                val after = proposed.getCustodyFor(date)
                if (before == after) null else MovedDay(date, before, after)
            }

            val net = mutableMapOf<String, Int>()
            moved.forEach { day ->
                net[day.fromSlot] = (net[day.fromSlot] ?: 0) - 1
                net[day.toSlot] = (net[day.toSlot] ?: 0) + 1
            }

            return CustodyPatternDiff(
                movedDays = moved,
                netDaysBySlot = net.filterValues { it != 0 },
                windowDays = window,
                identical = moved.isEmpty(),
                comparable = true
            )
        }

        /**
         * The scan length: the two cycles' least common multiple when it fits, else the cap.
         *
         * Computed in [Long] for the reason `CustodyModel.isEquivalentTo` documents — the
         * product of two three-digit cycle lengths overflows `Int` well before it overflows
         * `Long`, and a wrapped negative window is an empty range that answers "no change".
         */
        private fun windowFor(a: Int, b: Int, cap: Int): Int {
            val lcm = a.toLong() / gcd(a.toLong(), b.toLong()) * b.toLong()
            return if (lcm in 1..cap.toLong()) lcm.toInt() else cap
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
