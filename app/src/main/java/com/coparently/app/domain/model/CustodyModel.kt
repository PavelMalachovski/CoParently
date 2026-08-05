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
     */
    fun complemented(): CustodyModel =
        copy(momDayIndices = (0 until patternDays).toSet() - momDayIndices)

    /**
     * Whether [other] assigns custody the same way this model does, on every day.
     *
     * Compared by outcome rather than by field: two models with start dates a whole number of
     * cycles apart describe the same schedule, and two different [modelType]s can produce
     * identical assignments. The window is the least common multiple of the two cycle lengths,
     * because a shorter window can make a 14-day and a 21-day pattern look identical.
     */
    fun isEquivalentTo(other: CustodyModel): Boolean {
        if (patternDays <= 0 || other.patternDays <= 0) return false
        val window = lcm(patternDays, other.patternDays)
        val from = minOf(startDate, other.startDate)
        return (0 until window).all { offset ->
            val date = from.plusDays(offset.toLong())
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
    TWO_TWO_THREE("2-2-3 Split"),
    THREE_FOUR_FOUR_THREE("3-4-4-3 Split"),
    CUSTOM("Custom Schedule");

    companion object {
        fun fromString(value: String): CustodyModelType {
            return when (value.lowercase()) {
                "week_on_week_off" -> WEEK_ON_WEEK_OFF
                "2_2_3" -> TWO_TWO_THREE
                "3_4_4_3" -> THREE_FOUR_FOUR_THREE
                "custom" -> CUSTOM
                else -> CUSTOM
            }
        }

        fun toString(type: CustodyModelType): String {
            return when (type) {
                WEEK_ON_WEEK_OFF -> "week_on_week_off"
                TWO_TWO_THREE -> "2_2_3"
                THREE_FOUR_FOUR_THREE -> "3_4_4_3"
                CUSTOM -> "custom"
            }
        }
    }
}

/** Least common multiple, for sizing the comparison window in [CustodyModel.isEquivalentTo]. */
private fun lcm(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return a / x * b
}
