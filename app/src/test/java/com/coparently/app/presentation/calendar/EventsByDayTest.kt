package com.coparently.app.presentation.calendar

import com.coparently.app.domain.model.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The month grid indexes [eventsByDay] once per event list instead of scanning the whole list in
 * each of its 42 cells. That is only safe if the index answers exactly what [eventsOn] answers —
 * the agenda card under the grid and the dots above it must never disagree about which day an
 * event belongs to, and multi-day/overnight spans are a documented past bug in this project.
 */
class EventsByDayTest {

    private val base = LocalDate.of(2026, 8, 10)

    private fun event(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime? = null
    ) = Event(
        id = id,
        title = id,
        startDateTime = start,
        endDateTime = end,
        eventType = "general",
        parentOwner = "mom",
        createdAt = start,
        updatedAt = start
    )

    private fun idsOn(events: List<Event>, date: LocalDate): List<String> =
        eventsByDay(events)[date].orEmpty().map { it.id }

    @Test
    fun `an event inside one day lands on that day only`() {
        val events = listOf(
            event("single", base.atTime(9, 0), base.atTime(10, 0))
        )

        assertEquals(listOf("single"), idsOn(events, base))
        assertEquals(emptyList<String>(), idsOn(events, base.minusDays(1)))
        assertEquals(emptyList<String>(), idsOn(events, base.plusDays(1)))
    }

    @Test
    fun `an event with no end time lands on its start day`() {
        val events = listOf(event("openEnded", base.atTime(9, 0)))

        assertEquals(listOf("openEnded"), idsOn(events, base))
        assertEquals(emptyList<String>(), idsOn(events, base.plusDays(1)))
    }

    @Test
    fun `an overnight event lands on both days it touches`() {
        val events = listOf(
            event("overnight", base.atTime(22, 0), base.plusDays(1).atTime(6, 0))
        )

        assertEquals(listOf("overnight"), idsOn(events, base))
        assertEquals(listOf("overnight"), idsOn(events, base.plusDays(1)))
        assertEquals(emptyList<String>(), idsOn(events, base.plusDays(2)))
    }

    @Test
    fun `a three-day event appears on all three days`() {
        val events = listOf(
            event("camp", base.atTime(8, 0), base.plusDays(2).atTime(17, 0))
        )

        assertEquals(listOf("camp"), idsOn(events, base))
        assertEquals(listOf("camp"), idsOn(events, base.plusDays(1)))
        assertEquals(listOf("camp"), idsOn(events, base.plusDays(2)))
        assertEquals(emptyList<String>(), idsOn(events, base.plusDays(3)))
        assertEquals(emptyList<String>(), idsOn(events, base.minusDays(1)))
    }

    @Test
    fun `an event ending exactly at midnight still counts for that midnight's day`() {
        // eventsOn tests `end >= dayStart`, so an event ending at 00:00 covers the day that
        // starts then. The index must not "helpfully" trim it.
        val events = listOf(
            event("untilMidnight", base.atTime(20, 0), base.plusDays(1).atStartOfDay())
        )

        assertEquals(listOf("untilMidnight"), idsOn(events, base))
        assertEquals(listOf("untilMidnight"), idsOn(events, base.plusDays(1)))
        assertEquals(emptyList<String>(), idsOn(events, base.plusDays(2)))
    }

    @Test
    fun `an event outside the queried days is not indexed anywhere near them`() {
        val events = listOf(
            event("faraway", base.plusMonths(2).atTime(9, 0), base.plusMonths(2).atTime(10, 0))
        )

        val index = eventsByDay(events)
        assertEquals(setOf(base.plusMonths(2)), index.keys)
    }

    @Test
    fun `days are ordered by start time`() {
        val events = listOf(
            event("late", base.atTime(18, 0), base.atTime(19, 0)),
            event("early", base.atTime(7, 0), base.atTime(8, 0)),
            event("middaySpanning", base.minusDays(1).atTime(12, 0), base.atTime(13, 0))
        )

        // The spanning event started yesterday, so it sorts first on this day too.
        assertEquals(listOf("middaySpanning", "early", "late"), idsOn(events, base))
    }

    @Test
    fun `the index agrees with eventsOn on every day it touches`() {
        val events = listOf(
            event("single", base.atTime(9, 0), base.atTime(10, 0)),
            event("openEnded", base.plusDays(1).atTime(9, 0)),
            event("overnight", base.plusDays(2).atTime(22, 0), base.plusDays(3).atTime(6, 0)),
            event("camp", base.minusDays(1).atTime(8, 0), base.plusDays(4).atTime(17, 0)),
            event("untilMidnight", base.atTime(20, 0), base.plusDays(1).atStartOfDay())
        )

        val index = eventsByDay(events)
        for (offset in -5L..10L) {
            val date = base.plusDays(offset)
            assertEquals(
                "index disagrees with eventsOn on $date",
                eventsOn(events, date),
                index[date].orEmpty()
            )
        }
    }

    @Test
    fun `an empty list produces an empty index`() {
        assertEquals(emptyMap<LocalDate, List<Event>>(), eventsByDay(emptyList()))
    }
}
