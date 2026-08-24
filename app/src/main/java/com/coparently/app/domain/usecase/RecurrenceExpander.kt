package com.coparently.app.domain.usecase

import com.coparently.app.domain.model.Event
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Expands recurring events into concrete occurrences within a date range.
 *
 * Occurrences keep the id of their master event so tapping one opens the
 * master for editing. Supported patterns: "daily", "weekly", "biweekly", "monthly".
 *
 * **Occurrences are indexed, not walked.** The nth occurrence is a pure function of the master
 * event's start and its pattern — [occurrenceAt] — and the expansion jumps straight to the first
 * index the queried range can contain. Two consequences, both of them fixes:
 *
 * 1. A range far from the event's start costs the same as a near one. The previous
 *    implementation stepped one interval at a time from `startDateTime` and stopped after
 *    [MAX_OCCURRENCES] **loop iterations**, so a *daily* event simply ceased to exist 730 days
 *    after it began: ask for a month three years out and the answer was an empty list, while the
 *    master row sat intact in Room. The calendar did not lose the event, it lied about it.
 * 2. The same occurrence lands on the same day whichever window asks for it. Walking
 *    `plusMonths(1)` repeatedly clamps *and keeps* the clamp — a monthly event on the 31st
 *    became the 28th the first time it crossed February and stayed there — so the answer
 *    depended on where the walk started. Indexing from the master start restores the 31st, and
 *    makes the window irrelevant.
 */
object RecurrenceExpander {

    /**
     * Safety cap on occurrences **emitted**, so a pathological range cannot allocate without
     * bound. It is not a horizon: skipped occurrences before the range no longer count against
     * it, which is the whole of defect 1 above.
     */
    private const val MAX_OCCURRENCES = 730

    private const val BIWEEKLY_WEEKS = 2L

    /**
     * Expands a recurring event into occurrences overlapping [rangeStart, rangeEnd].
     * Returns the event as-is (single occurrence) when it is not recurring.
     */
    fun expand(event: Event, rangeStart: LocalDateTime, rangeEnd: LocalDateTime): List<Event> {
        if (!event.isRecurring || event.recurrencePattern.isNullOrBlank()) {
            return if (event.startDateTime <= rangeEnd &&
                (event.endDateTime ?: event.startDateTime) >= rangeStart
            ) listOf(event) else emptyList()
        }

        val pattern = event.recurrencePattern
        val duration = event.endDateTime?.let { Duration.between(event.startDateTime, it) }
        val recurrenceEnd = event.recurrenceEndDate?.atTime(23, 59, 59)

        val occurrences = mutableListOf<Event>()
        var index = firstIndexEndingAtOrAfter(event.startDateTime, pattern, rangeStart, duration)

        while (occurrences.size < MAX_OCCURRENCES) {
            val start = occurrenceAt(event.startDateTime, pattern, index) ?: break
            if (start > rangeEnd) break
            if (recurrenceEnd != null && start > recurrenceEnd) break

            val end = duration?.let { start.plus(it) }
            if ((end ?: start) >= rangeStart) {
                occurrences.add(event.copy(startDateTime = start, endDateTime = end))
            }
            index++
        }

        return occurrences
    }

    /**
     * Expands every event in the list against the given range and sorts the result.
     */
    fun expandAll(events: List<Event>, rangeStart: LocalDateTime, rangeEnd: LocalDateTime): List<Event> {
        return events
            .flatMap { expand(it, rangeStart, rangeEnd) }
            .sortedBy { it.startDateTime }
    }

    /**
     * The start of occurrence [index], counting the master event itself as index 0, or null when
     * [pattern] has no successor — an unrecognised pattern yields the master and nothing after it.
     *
     * Every offset is taken from [start] rather than from the previous occurrence, so month-end
     * clamping cannot accumulate: the 31st of January plus two months is the 31st of March, not
     * the 28th.
     */
    private fun occurrenceAt(start: LocalDateTime, pattern: String, index: Long): LocalDateTime? =
        when (pattern) {
            "daily" -> start.plusDays(index)
            "weekly" -> start.plusWeeks(index)
            "biweekly" -> start.plusWeeks(index * BIWEEKLY_WEEKS)
            "monthly" -> start.plusMonths(index)
            else -> start.takeIf { index == 0L }
        }

    /**
     * The lowest index whose occurrence has not already finished before [rangeStart] — the first
     * one the range can contain.
     *
     * Derived arithmetically rather than by walking, which is what makes a distant range cheap.
     * An occurrence that *starts* before the range still belongs to it if it has not ended yet,
     * so the target is offset by the event's own duration; an overnight event beginning at 22:00
     * the previous evening is exactly the case [com.coparently.app.data.repository.EventRepositoryImpl]
     * queries by overlap for.
     */
    private fun firstIndexEndingAtOrAfter(
        start: LocalDateTime,
        pattern: String,
        rangeStart: LocalDateTime,
        duration: Duration?
    ): Long {
        // A negative duration is malformed data (an event ending before it starts). Offsetting by
        // it would move the target *later* and skip occurrences the emit loop would have kept, so
        // such an event is treated as instantaneous here and judged by the loop, as before.
        val target = duration
            ?.takeUnless { it.isNegative }
            ?.let { rangeStart.minus(it) }
            ?: rangeStart
        if (start >= target) return 0

        // ChronoUnit.between truncates, so this lands on or just before the target — never past
        // it. At most one correction is therefore needed, and it is the immediately next
        // occurrence, so the first index at or after the target cannot be skipped.
        val estimate = when (pattern) {
            "daily" -> ChronoUnit.DAYS.between(start, target)
            "weekly" -> ChronoUnit.WEEKS.between(start, target)
            "biweekly" -> ChronoUnit.WEEKS.between(start, target) / BIWEEKLY_WEEKS
            "monthly" -> ChronoUnit.MONTHS.between(start, target)
            else -> return 0
        }.coerceAtLeast(0)

        val candidate = occurrenceAt(start, pattern, estimate) ?: return 0
        return if (candidate < target) estimate + 1 else estimate
    }
}
