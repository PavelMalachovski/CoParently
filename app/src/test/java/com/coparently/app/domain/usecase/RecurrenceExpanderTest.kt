package com.coparently.app.domain.usecase

import com.coparently.app.domain.model.Event
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for RecurrenceExpander.
 * Pure logic - no mocks needed.
 */
class RecurrenceExpanderTest {

    private fun event(
        start: LocalDateTime,
        pattern: String? = null,
        endDate: LocalDate? = null,
        durationHours: Long = 1
    ) = Event(
        id = "e1",
        title = "Training",
        startDateTime = start,
        endDateTime = start.plusHours(durationHours),
        eventType = "sports",
        parentOwner = "mom",
        isRecurring = pattern != null,
        recurrencePattern = pattern,
        recurrenceEndDate = endDate,
        createdAt = start,
        updatedAt = start
    )

    private val rangeStart = LocalDateTime.of(2026, 7, 1, 0, 0)
    private val rangeEnd = LocalDateTime.of(2026, 7, 31, 23, 59)

    @Test
    fun `non-recurring event inside range returns single occurrence`() {
        val e = event(LocalDateTime.of(2026, 7, 10, 9, 0))
        val result = RecurrenceExpander.expand(e, rangeStart, rangeEnd)
        assertEquals(1, result.size)
        assertEquals(e.startDateTime, result.first().startDateTime)
    }

    @Test
    fun `non-recurring event outside range returns nothing`() {
        val e = event(LocalDateTime.of(2026, 8, 10, 9, 0))
        assertTrue(RecurrenceExpander.expand(e, rangeStart, rangeEnd).isEmpty())
    }

    @Test
    fun `weekly event expands into all occurrences in range`() {
        val e = event(LocalDateTime.of(2026, 7, 1, 9, 0), pattern = "weekly")
        val result = RecurrenceExpander.expand(e, rangeStart, rangeEnd)
        // 1, 8, 15, 22, 29 July
        assertEquals(5, result.size)
        assertEquals(LocalDateTime.of(2026, 7, 29, 9, 0), result.last().startDateTime)
    }

    @Test
    fun `daily event started before range only returns occurrences inside range`() {
        val e = event(LocalDateTime.of(2026, 6, 25, 8, 0), pattern = "daily")
        val result = RecurrenceExpander.expand(e, rangeStart, rangeEnd)
        assertEquals(31, result.size)
        assertEquals(LocalDate.of(2026, 7, 1), result.first().startDateTime.toLocalDate())
    }

    @Test
    fun `recurrence end date stops expansion`() {
        val e = event(
            LocalDateTime.of(2026, 7, 1, 9, 0),
            pattern = "weekly",
            endDate = LocalDate.of(2026, 7, 15)
        )
        val result = RecurrenceExpander.expand(e, rangeStart, rangeEnd)
        // 1, 8, 15 July - the 22nd is beyond the end date
        assertEquals(3, result.size)
    }

    @Test
    fun `biweekly and monthly patterns step correctly`() {
        val biweekly = event(LocalDateTime.of(2026, 7, 1, 9, 0), pattern = "biweekly")
        assertEquals(3, RecurrenceExpander.expand(biweekly, rangeStart, rangeEnd).size) // 1, 15, 29

        val monthly = event(LocalDateTime.of(2026, 5, 10, 9, 0), pattern = "monthly")
        val result = RecurrenceExpander.expand(monthly, rangeStart, rangeEnd)
        assertEquals(1, result.size) // only 10 July falls into the range
        assertEquals(LocalDate.of(2026, 7, 10), result.first().startDateTime.toLocalDate())
    }

    @Test
    fun `occurrences keep master event id`() {
        val e = event(LocalDateTime.of(2026, 7, 1, 9, 0), pattern = "weekly")
        RecurrenceExpander.expand(e, rangeStart, rangeEnd).forEach {
            assertEquals("e1", it.id)
        }
    }

    @Test
    fun `unknown pattern yields only the first occurrence`() {
        val e = event(LocalDateTime.of(2026, 7, 1, 9, 0), pattern = "every-full-moon")
        assertEquals(1, RecurrenceExpander.expand(e, rangeStart, rangeEnd).size)
    }

    @Test
    fun `expandAll merges and sorts occurrences`() {
        val a = event(LocalDateTime.of(2026, 7, 5, 9, 0))
        val b = event(LocalDateTime.of(2026, 7, 1, 9, 0), pattern = "weekly").copy(id = "e2")
        val result = RecurrenceExpander.expandAll(listOf(a, b), rangeStart, rangeEnd)
        assertEquals(6, result.size)
        assertTrue(result.zipWithNext().all { (x, y) -> x.startDateTime <= y.startDateTime })
    }

    // ---- indexed expansion (the 730-iteration horizon, and the month-end drift) ----

    @Test
    fun `a daily event still exists three years after it started`() {
        // The defect this file exists to pin: the walk stepped one interval at a time from the
        // master start and stopped after 730 *iterations*, so a daily event ceased to exist 730
        // days in. The master row was intact; the calendar simply returned nothing.
        val e = event(LocalDateTime.of(2026, 1, 1, 8, 0), pattern = "daily")

        val result = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2029, 7, 1, 0, 0),
            LocalDateTime.of(2029, 7, 31, 23, 59)
        )

        assertEquals(31, result.size)
        assertEquals(LocalDate.of(2029, 7, 1), result.first().startDateTime.toLocalDate())
        assertEquals(LocalDate.of(2029, 7, 31), result.last().startDateTime.toLocalDate())
    }

    @Test
    fun `a weekly event a decade out is not truncated either`() {
        val e = event(LocalDateTime.of(2026, 1, 7, 8, 0), pattern = "weekly")

        val result = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2040, 3, 1, 0, 0),
            LocalDateTime.of(2040, 3, 31, 23, 59)
        )

        assertTrue(result.isNotEmpty(), "a weekly event fourteen years out must still expand")
        result.forEach { assertEquals(e.startDateTime.dayOfWeek, it.startDateTime.dayOfWeek) }
    }

    @Test
    fun `a monthly event on the 31st does not drift to the 28th`() {
        // Walking `plusMonths(1)` clamps to the end of February and then keeps the clamped day
        // forever, so a monthly event on the 31st silently became a monthly event on the 28th.
        // Indexing from the master start restores it.
        val e = event(LocalDateTime.of(2026, 1, 31, 9, 0), pattern = "monthly")

        val march = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2026, 3, 1, 0, 0),
            LocalDateTime.of(2026, 3, 31, 23, 59)
        )

        assertEquals(1, march.size)
        assertEquals(LocalDate.of(2026, 3, 31), march.first().startDateTime.toLocalDate())
    }

    @Test
    fun `February still clamps, because it has to`() {
        val e = event(LocalDateTime.of(2026, 1, 31, 9, 0), pattern = "monthly")

        val february = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2026, 2, 1, 0, 0),
            LocalDateTime.of(2026, 2, 28, 23, 59)
        )

        assertEquals(1, february.size)
        assertEquals(LocalDate.of(2026, 2, 28), february.first().startDateTime.toLocalDate())
    }

    @Test
    fun `an occurrence falls on the same day whichever window asks for it`() {
        // The invariant that makes the two tests above more than a pair of examples: an
        // occurrence is a function of the master event and its index, not of where a walk began.
        val e = event(LocalDateTime.of(2026, 1, 31, 9, 0), pattern = "monthly")

        val fromTheWholeYear = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 12, 31, 23, 59)
        ).map { it.startDateTime }.filter { it.monthValue == 7 }

        val fromJulyAlone = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2026, 7, 1, 0, 0),
            LocalDateTime.of(2026, 7, 31, 23, 59)
        ).map { it.startDateTime }

        assertEquals(fromTheWholeYear, fromJulyAlone)
    }

    @Test
    fun `an overnight occurrence that began the previous evening is still returned`() {
        // The range is matched by overlap, not by start date, so the skip-ahead must offset by
        // the event's own duration or it would drop the handover that runs 22:00 to 02:00.
        val e = event(LocalDateTime.of(2026, 6, 25, 22, 0), pattern = "daily", durationHours = 4)

        val result = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2026, 7, 1, 0, 0),
            LocalDateTime.of(2026, 7, 1, 23, 59)
        )

        assertEquals(2, result.size)
        assertEquals(LocalDate.of(2026, 6, 30), result.first().startDateTime.toLocalDate())
        assertEquals(LocalDate.of(2026, 7, 1), result.last().startDateTime.toLocalDate())
    }

    @Test
    fun `a recurrence end date long past still stops expansion`() {
        val e = event(
            LocalDateTime.of(2026, 1, 1, 9, 0),
            pattern = "daily",
            endDate = LocalDate.of(2026, 1, 10)
        )

        val result = RecurrenceExpander.expand(
            e,
            LocalDateTime.of(2029, 7, 1, 0, 0),
            LocalDateTime.of(2029, 7, 31, 23, 59)
        )

        assertTrue(result.isEmpty(), "an event whose recurrence ended in 2026 has no 2029 occurrences")
    }
}
