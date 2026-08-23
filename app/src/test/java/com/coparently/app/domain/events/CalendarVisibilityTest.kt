package com.coparently.app.domain.events

import com.coparently.app.domain.model.Event
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * What a calendar view may draw.
 *
 * The two that must be absent are absent for different reasons. A `PENDING` event has not been
 * agreed to, and a grid that draws unagreed events is the problem this package removes. A
 * `DECLINED` one has been refused but is deliberately not deleted, so it needs keeping out of the
 * grid as well — otherwise "no" would look exactly like "yes".
 */
class CalendarVisibilityTest {

    private val at = LocalDateTime.of(2026, 9, 1, 10, 0)

    private fun event(id: String, acceptance: EventAcceptance) = Event(
        id = id,
        title = id,
        startDateTime = at,
        eventType = "training",
        parentOwner = "dad",
        createdAt = at,
        updatedAt = at,
        acceptance = acceptance
    )

    @Test
    fun `an ordinary event and an accepted one are drawn`() {
        val events = listOf(
            event("ordinary", EventAcceptance.NOT_REQUIRED),
            event("accepted", EventAcceptance.ACCEPTED)
        )

        assertEquals(events, CalendarVisibility.visible(events))
    }

    @Test
    fun `a pending event is not drawn, because nobody has agreed to it`() {
        val events = listOf(event("pending", EventAcceptance.PENDING))

        assertEquals(emptyList(), CalendarVisibility.visible(events))
    }

    @Test
    fun `a declined event is not drawn either, or no would look like yes`() {
        val events = listOf(event("declined", EventAcceptance.DECLINED))

        assertEquals(emptyList(), CalendarVisibility.visible(events))
    }

    @Test
    fun `filtering keeps the rest and their order`() {
        val events = listOf(
            event("a", EventAcceptance.NOT_REQUIRED),
            event("b", EventAcceptance.PENDING),
            event("c", EventAcceptance.ACCEPTED),
            event("d", EventAcceptance.DECLINED)
        )

        assertEquals(listOf("a", "c"), CalendarVisibility.visible(events).map { it.id })
    }
}
