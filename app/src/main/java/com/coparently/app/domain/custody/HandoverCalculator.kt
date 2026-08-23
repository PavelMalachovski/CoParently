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
 * Lives in the domain layer because the home dashboard's handover hero asks the same question
 * the grid does. Keeping one implementation stops them drifting into disagreeing about a date
 * two parents are planning around.
 */
object HandoverCalculator {

    /**
     * Walks forward from [today] to the first day custody switches to the other parent.
     *
     * Bounded by two full pattern cycles, so it always terminates: a model that never switches
     * (a single parent holding every day) returns null rather than looping.
     *
     * **Resolves each day through [CustodyResolver], not through [CustodyModel.getCustodyFor].**
     * A one-off swap both creates a handover on the day it moves and removes the one it
     * displaced, and a calculator reading the pattern directly would report a date that is simply
     * wrong — silently, on exactly the days a swap touched, while the grid beside it painted the
     * swap correctly. Nothing fails when this is got wrong, which is why it is spelled out here.
     *
     * @param model Active custody model
     * @param today Day to search forward from
     * @param overrides Accepted one-off swaps keyed by ISO date; pending and declined ones move
     *   nothing, and an empty map reproduces the pre-swap behaviour exactly
     * @return The next handover, or null when custody never changes within two cycles
     */
    fun nextHandoverFrom(
        model: CustodyModel,
        today: LocalDate,
        overrides: Map<String, DayOverride> = emptyMap()
    ): HandoverInfo? {
        val custodyFor = CustodyResolver.resolver(model, overrides, legacy = { null })
        val current = custodyFor(today) ?: return null
        val maxDays = model.patternDays * 2 + 1
        var day = today
        repeat(maxDays) {
            val next = day.plusDays(1)
            val nextParent = custodyFor(next) ?: return null
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
