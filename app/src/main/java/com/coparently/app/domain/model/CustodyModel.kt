package com.coparently.app.domain.model

import java.time.LocalDate

/**
 * Domain model for custody configuration.
 * Represents the custody pattern that determines which parent has custody on any given date.
 *
 * @property id Unique identifier
 * @property modelType Type of custody pattern
 * @property patternDays Total days in the pattern cycle
 * @property momDayIndices Set of day indices (0-based) within the pattern when mom has custody
 * @property startDate Anchor date for calculating pattern position
 * @property isActive Whether this model is currently in use
 */
data class CustodyModel(
    val id: String,
    val modelType: CustodyModelType,
    val patternDays: Int,
    val momDayIndices: Set<Int>,
    val startDate: LocalDate,
    val isActive: Boolean = true
) {
    /**
     * Determines which parent has custody on the given date.
     *
     * @param date The date to check
     * @return "mom" or "dad"
     */
    fun getCustodyFor(date: LocalDate): String {
        val daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(startDate, date).toInt()
        // Handle negative days (dates before start)
        val adjustedDays = ((daysSinceStart % patternDays) + patternDays) % patternDays
        return if (momDayIndices.contains(adjustedDays)) "mom" else "dad"
    }

    /**
     * This pattern with the two slots swapped.
     *
     * [momDayIndices] means "the days slot 1 has custody". When pairing moves this device to
     * the other slot, the same set would silently start describing the co-parent's days, so
     * the set is complemented to keep meaning "my days".
     *
     * Getting this wrong is not a cosmetic bug: the pairing conflict screen would offer a
     * parent their own schedule inverted, they would reject it, and hand over exactly the days
     * they meant to keep.
     *
     * A non-positive [patternDays] has no cycle to complement: `(0 until patternDays).toSet() -
     * momDayIndices` would be `emptySet()` regardless of what [momDayIndices] held, silently
     * discarding it. This returns the model unchanged instead, the same way [isEquivalentTo]
     * refuses rather than guesses when it cannot make sense of a cycle length.
     *
     * An index in [momDayIndices] outside `0 until patternDays` is dropped, not preserved: it
     * can never match in [getCustodyFor], which reduces every offset into that range before
     * testing membership, so a model with or without it produces identical custody outcomes.
     */
    fun complemented(): CustodyModel {
        if (patternDays <= 0) return this
        return copy(momDayIndices = (0 until patternDays).toSet() - momDayIndices)
    }

    /**
     * Whether [other] assigns custody the same way this model does, on every day.
     *
     * Compared by outcome rather than by field: two models with start dates a whole number of
     * cycles apart describe the same schedule, and two different [modelType]s can produce
     * identical assignments. The window is the least common multiple of the two cycle lengths,
     * because a shorter window can make a 14-day and a 21-day pattern look identical.
     *
     * A [patternDays] outside `1..MAX_SANE_PATTERN_DAYS` on either side is refused rather than
     * compared: no real custody arrangement repeats on a cycle longer than a year, and the only
     * path that can produce one is an unvalidated Firestore document synced from the other
     * device, since [patternDays] never reaches this class un-clamped from the app's own UI.
     * Without the bound, two large-enough cycle lengths push their least common multiple past
     * [Int.MAX_VALUE]; unguarded `Int` arithmetic wraps that to a negative number, and
     * `(0 until window)` on a negative window is an empty range, so `.all { }` returns `true`
     * for two schedules that were never actually compared. The same bound keeps this a
     * bounded, synchronous scan, since it can run on whatever thread calls it, including the
     * pairing conflict screen.
     */
    fun isEquivalentTo(other: CustodyModel): Boolean {
        if (patternDays !in 1..MAX_SANE_PATTERN_DAYS || other.patternDays !in 1..MAX_SANE_PATTERN_DAYS) {
            return false
        }
        val window = lcm(patternDays, other.patternDays)
        val from = minOf(startDate, other.startDate)
        return (0 until window).all { offset ->
            val date = from.plusDays(offset)
            getCustodyFor(date) == other.getCustodyFor(date)
        }
    }

    companion object {
        /**
         * Creates a week-on-week-off pattern.
         * Mom has first week (days 0-6), Dad has second week (days 7-13).
         *
         * @param startDate The date when the first parent (mom) starts their week
         * @param momFirst If true, mom has the first week; if false, dad has the first week
         */
        fun weekOnWeekOff(
            id: String,
            startDate: LocalDate,
            momFirst: Boolean = true
        ): CustodyModel {
            val momDays = if (momFirst) {
                (0..6).toSet()
            } else {
                (7..13).toSet()
            }
            return CustodyModel(
                id = id,
                modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
                patternDays = 14,
                momDayIndices = momDays,
                startDate = startDate
            )
        }

        /**
         * Creates an every-other-weekend pattern — one parent's home, the other's alternate
         * weekends.
         *
         * This is **výhradní péče se stykem**, the arrangement a large share of Czech families
         * actually have, and until now the only preset list it appeared in was `CUSTOM`. The
         * three patterns beside it split the time roughly in half; this one does not, and a
         * parent whose court order says every second weekend had to build fourteen days by hand
         * on the first screen they meet.
         *
         * Fourteen days, anchored the same way [weekOnWeekOff] is: [startDate] is day 0 and is
         * expected to be the **Monday** the cycle opens on, which makes days 5 and 6 the first
         * Saturday and Sunday. The resident parent holds everything else. There is no separate
         * anchor validation here because there is none for the other patterns either — the
         * preview card is what shows a parent they picked the wrong day.
         *
         * **Whole days only, and that is a real limitation rather than a simplification.** Most
         * such orders also give the other parent a midweek afternoon, and this model assigns a
         * day to exactly one parent — there is no half-day to give. Folding the afternoon into a
         * whole Wednesday would hand over an overnight nobody agreed to, which is the sort of
         * quiet wrongness this schedule must never produce, so the preset leaves it out and says
         * so. A parent who needs it can still describe the fortnight in `CUSTOM`.
         *
         * @param momIsResident True when slot 1 is the parent the child lives with; false when
         *   slot 1 is the parent with the alternate weekends.
         */
        fun everyOtherWeekend(
            id: String,
            startDate: LocalDate,
            momIsResident: Boolean = true
        ): CustodyModel {
            val contactWeekend = setOf(5, 6)
            val momDays = if (momIsResident) {
                (0..13).toSet() - contactWeekend
            } else {
                contactWeekend
            }
            return CustodyModel(
                id = id,
                modelType = CustodyModelType.EVERY_OTHER_WEEKEND,
                patternDays = 14,
                momDayIndices = momDays,
                startDate = startDate
            )
        }

        /**
         * Creates a 2-2-3 pattern.
         * Pattern over 2 weeks:
         * Week 1: Mom Mon-Tue, Dad Wed-Thu, Mom Fri-Sun
         * Week 2: Dad Mon-Tue, Mom Wed-Thu, Dad Fri-Sun
         */
        fun twoTwoThree(
            id: String,
            startDate: LocalDate,
            momStartsFirst: Boolean = true
        ): CustodyModel {
            // 2-2-3 pattern repeats every 14 days
            val momDays = if (momStartsFirst) {
                setOf(0, 1, 4, 5, 6, 9, 10) // Mon-Tue, Fri-Sun in week 1; Wed-Thu in week 2
            } else {
                setOf(2, 3, 7, 8, 11, 12, 13) // Wed-Thu, Mon-Tue in week 2, Fri-Sun in week 2
            }
            return CustodyModel(
                id = id,
                modelType = CustodyModelType.TWO_TWO_THREE,
                patternDays = 14,
                momDayIndices = momDays,
                startDate = startDate
            )
        }

        /**
         * Creates a 3-4-4-3 pattern (alternating 3 and 4 day blocks).
         * Week 1: Mom Mon-Wed (3), Dad Thu-Sun (4)
         * Week 2: Dad Mon-Thu (4), Mom Fri-Sun (3)
         */
        fun threeFourFourThree(
            id: String,
            startDate: LocalDate,
            momStartsFirst: Boolean = true
        ): CustodyModel {
            val momDays = if (momStartsFirst) {
                setOf(0, 1, 2, 11, 12, 13) // Mon-Wed in week 1, Fri-Sun in week 2
            } else {
                setOf(3, 4, 5, 6, 7, 8, 9, 10) // Thu-Sun in week 1, Mon-Thu in week 2
            }
            return CustodyModel(
                id = id,
                modelType = CustodyModelType.THREE_FOUR_FOUR_THREE,
                patternDays = 14,
                momDayIndices = momDays,
                startDate = startDate
            )
        }

        /**
         * Creates a custom pattern.
         *
         * @param patternDays Total days in the pattern cycle
         * @param momDayIndices Indices (0-based) within the pattern when mom has custody
         */
        fun custom(
            id: String,
            startDate: LocalDate,
            patternDays: Int,
            momDayIndices: Set<Int>
        ): CustodyModel {
            return CustodyModel(
                id = id,
                modelType = CustodyModelType.CUSTOM,
                patternDays = patternDays,
                momDayIndices = momDayIndices,
                startDate = startDate
            )
        }
    }
}

/**
 * Enum representing different types of custody models.
 */
enum class CustodyModelType(val displayName: String) {
    WEEK_ON_WEEK_OFF("Week On / Week Off"),

    /**
     * Sole custody with contact every second weekend — `výhradní péče se stykem`.
     *
     * Listed second, immediately after week-on-week-off, because those two are the arrangements
     * Czech families actually have; the two below them are US family-law vocabulary. The order
     * of this enum *is* the order of the picker (`CustodyModelType.entries.forEach`), so this is
     * the whole of the placement decision. Whether the two American patterns still earn a place
     * in a Czech-first launch is an owner's call and is deliberately not made here — removing
     * one would leave existing users' saved `modelType` unparseable.
     */
    EVERY_OTHER_WEEKEND("Every Other Weekend"),
    TWO_TWO_THREE("2-2-3 Split"),
    THREE_FOUR_FOUR_THREE("3-4-4-3 Split"),
    CUSTOM("Custom Schedule");

    companion object {
        fun fromString(value: String): CustodyModelType {
            return when (value.lowercase()) {
                "week_on_week_off" -> WEEK_ON_WEEK_OFF
                "every_other_weekend" -> EVERY_OTHER_WEEKEND
                "2_2_3" -> TWO_TWO_THREE
                "3_4_4_3" -> THREE_FOUR_FOUR_THREE
                "custom" -> CUSTOM
                else -> CUSTOM
            }
        }

        fun toString(type: CustodyModelType): String {
            return when (type) {
                WEEK_ON_WEEK_OFF -> "week_on_week_off"
                EVERY_OTHER_WEEKEND -> "every_other_weekend"
                TWO_TWO_THREE -> "2_2_3"
                THREE_FOUR_FOUR_THREE -> "3_4_4_3"
                CUSTOM -> "custom"
            }
        }
    }
}

/**
 * Upper bound on a single custody cycle length, in days, accepted by [CustodyModel.isEquivalentTo].
 * No real custody arrangement repeats on a cycle longer than a year; [CustodySetupViewModel]'s
 * own custom-pattern input is clamped to 7..28, so a [CustodyModel.patternDays] past this bound
 * can only have arrived unvalidated, from a Firestore document synced from the other device.
 */
private const val MAX_SANE_PATTERN_DAYS = 366

/**
 * Least common multiple, for sizing the comparison window in [CustodyModel.isEquivalentTo].
 *
 * Computed in [Long]: [isEquivalentTo] bounds both cycle lengths to [MAX_SANE_PATTERN_DAYS]
 * before calling this, which already keeps the result far under [Int.MAX_VALUE], but the [Long]
 * arithmetic is the actual guard against overflow - the bound is what keeps the scan fast, not
 * what keeps this calculation correct.
 */
private fun lcm(a: Int, b: Int): Long {
    val x = a.toLong()
    val y = b.toLong()
    return x / gcd(x, y) * y
}

/** Greatest common divisor, via the Euclidean algorithm, for [lcm]. */
private fun gcd(a: Long, b: Long): Long {
    var x = a
    var y = b
    while (y != 0L) {
        val t = y
        y = x % y
        x = t
    }
    return x
}
