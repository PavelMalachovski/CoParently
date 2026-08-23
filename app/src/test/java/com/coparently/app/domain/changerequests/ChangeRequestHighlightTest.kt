package com.coparently.app.domain.changerequests

import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ChangeRequestHighlightTest {

    /** A request whose only interesting property is its id. */
    private fun anyRequest(id: String) = request(
        id = id,
        eventId = "e1",
        status = ChangeRequestStatus.PENDING,
        createdAt = LocalDateTime.of(2026, 8, 21, 9, 0)
    )

    private fun request(
        id: String,
        eventId: String,
        status: ChangeRequestStatus,
        createdAt: LocalDateTime
    ) = ChangeRequest(
        id = id,
        eventId = eventId,
        eventTitle = "Football",
        requestedBy = "me",
        requestedTo = "them",
        currentStartDateTime = LocalDateTime.of(2026, 8, 21, 10, 0),
        proposedStartDateTime = LocalDateTime.of(2026, 8, 21, 12, 0),
        status = status,
        createdAt = createdAt
    )

    @Test
    fun `no request for the event is not a match`() {
        val requests = listOf(
            request("r1", "other-event", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0))
        )

        assertNull(ChangeRequestHighlight.forEvent(requests, eventId = "e1"))
    }

    @Test
    fun `the pending request for the event wins`() {
        val requests = listOf(
            request("old", "e1", ChangeRequestStatus.DECLINED, LocalDateTime.of(2026, 8, 2, 9, 0)),
            request("live", "e1", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0))
        )

        assertEquals("live", ChangeRequestHighlight.forEvent(requests, eventId = "e1")?.id)
    }

    @Test
    fun `the newest pending request wins when the event collected several`() {
        val requests = listOf(
            request("first", "e1", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0)),
            request("second", "e1", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 3, 9, 0))
        )

        assertEquals("second", ChangeRequestHighlight.forEvent(requests, eventId = "e1")?.id)
    }

    @Test
    fun `an answered request is still worth showing when nothing is pending`() {
        val requests = listOf(
            request("older", "e1", ChangeRequestStatus.DECLINED, LocalDateTime.of(2026, 8, 1, 9, 0)),
            request("newer", "e1", ChangeRequestStatus.ACCEPTED, LocalDateTime.of(2026, 8, 4, 9, 0))
        )

        assertEquals("newer", ChangeRequestHighlight.forEvent(requests, eventId = "e1")?.id)
    }

    @Test
    fun `an empty inbox is not a match`() {
        assertNull(ChangeRequestHighlight.forEvent(emptyList(), eventId = "e1"))
    }

    // The inbox is one LazyColumn holding "Incoming" + its requests, then "Outgoing" + its
    // requests, with the two section headers as items of their own. Scrolling to a request means
    // counting those headers.

    @Test
    fun `an incoming request sits after its section header`() {
        val incoming = listOf(
            request("a", "e1", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0)),
            request("b", "e2", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 2, 9, 0))
        )

        assertEquals(2, ChangeRequestHighlight.indexInInbox(incoming, emptyList(), requestId = "b"))
    }

    @Test
    fun `an outgoing request sits after both sections`() {
        val incoming = listOf(
            request("a", "e1", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 1, 9, 0))
        )
        val outgoing = listOf(
            request("c", "e3", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 3, 9, 0))
        )

        // [0] Incoming header, [1] a, [2] Outgoing header, [3] c
        assertEquals(3, ChangeRequestHighlight.indexInInbox(incoming, outgoing, requestId = "c"))
    }

    @Test
    fun `an outgoing request needs no incoming header when there is no incoming section`() {
        val outgoing = listOf(
            request("c", "e3", ChangeRequestStatus.PENDING, LocalDateTime.of(2026, 8, 3, 9, 0))
        )

        assertEquals(1, ChangeRequestHighlight.indexInInbox(emptyList(), outgoing, requestId = "c"))
    }

    @Test
    fun `a request in neither section has no index`() {
        assertEquals(
            -1,
            ChangeRequestHighlight.indexInInbox(emptyList(), emptyList(), requestId = "nope")
        )
    }

    @Test
    fun `a day-swap section above the inbox shifts every index by its own size`() {
        // The swap section is a header plus one card per swap. Nothing here looks inside it —
        // this function is only ever asked about event change requests, because the highlight
        // arrives from a chat card about an event — but getting the offset wrong scrolls the
        // inbox to the wrong card, and nothing fails when it does.
        val incoming = listOf(anyRequest("a"), anyRequest("b"))
        val outgoing = listOf(anyRequest("c"))

        assertEquals(
            1 + ChangeRequestHighlight.indexInInbox(incoming, outgoing, "b"),
            ChangeRequestHighlight.indexInInbox(incoming, outgoing, "b", precedingItems = 1)
        )
        assertEquals(
            3 + ChangeRequestHighlight.indexInInbox(incoming, outgoing, "c"),
            ChangeRequestHighlight.indexInInbox(incoming, outgoing, "c", precedingItems = 3)
        )
    }

    @Test
    fun `an offset never rescues a request that is in neither section`() {
        assertEquals(
            -1,
            ChangeRequestHighlight.indexInInbox(emptyList(), emptyList(), "nope", precedingItems = 4)
        )
    }

}
