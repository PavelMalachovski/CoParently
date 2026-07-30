package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The next custody handover computed from an active custody model.
 *
 * @property date Day custody switches to the other parent
 * @property daysUntil Whole days from today to [date] (0 = today, 1 = tomorrow)
 * @property fromParent Parent who has custody now ("mom"/"dad")
 * @property toParent Parent who takes over on [date] ("mom"/"dad")
 */
data class HandoverInfo(
    val date: LocalDate,
    val daysUntil: Long,
    val fromParent: String,
    val toParent: String
)

/**
 * Works out when custody next changes hands.
 *
 * Lives in the domain layer because two screens ask the same question: the home dashboard's
 * "Handover in 4 days" tile and the calendar's custody ribbon. Keeping one implementation stops
 * them drifting into disagreeing about the same date.
 */
object HandoverCalculator {

    /**
     * Walks forward from [today] to the first day custody switches to the other parent.
     *
     * Bounded by two full pattern cycles, so it always terminates: a model that never switches
     * (a single parent holding every day) returns null rather than looping.
     *
     * @param model Active custody model
     * @param today Day to search forward from
     * @return The next handover, or null when custody never changes within two cycles
     */
    fun nextHandoverFrom(model: CustodyModel, today: LocalDate): HandoverInfo? {
        val current = model.getCustodyFor(today)
        val maxDays = model.patternDays * 2 + 1
        var day = today
        repeat(maxDays) {
            val next = day.plusDays(1)
            val nextParent = model.getCustodyFor(next)
            if (nextParent != current) {
                return HandoverInfo(
                    date = next,
                    daysUntil = ChronoUnit.DAYS.between(today, next),
                    fromParent = current,
                    toParent = nextParent
                )
            }
            day = next
        }
        return null
    }
}
