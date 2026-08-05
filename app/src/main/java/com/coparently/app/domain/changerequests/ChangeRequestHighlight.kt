package com.coparently.app.domain.changerequests

import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus

/**
 * Resolves the chat card's link target.
 *
 * The card posted into a thread carries the **event** id, not the request id
 * (`RequestChangeViewModel.postChatMessage` puts `event.id` in `attachments`), and one event can
 * collect several requests over its life. Tapping the card therefore has to pick one, and the
 * only useful pick is the one the reader can still act on.
 */
object ChangeRequestHighlight {

    /**
     * @param requests Every request the inbox knows about, in any order.
     * @param eventId Event the tapped card refers to.
     * @return The newest pending request for that event; failing that the newest request of any
     *   status, so a card for an already-answered proposal still explains itself; null when the
     *   event has no requests at all.
     */
    fun forEvent(requests: List<ChangeRequest>, eventId: String): ChangeRequest? {
        val forEvent = requests.filter { it.eventId == eventId }
        if (forEvent.isEmpty()) return null

        return forEvent
            .filter { it.status == ChangeRequestStatus.PENDING }
            .maxByOrNull { it.createdAt }
            ?: forEvent.maxByOrNull { it.createdAt }
    }

    /**
     * Position of [requestId] in the inbox's flat list, so it can be scrolled into view.
     *
     * The inbox renders one `LazyColumn` of "Incoming" header + incoming requests + "Outgoing"
     * header + outgoing requests, and each header is an item, so a request's index is not its
     * index in its own section.
     *
     * @return The item index, or -1 when the request is in neither section.
     */
    fun indexInInbox(
        incoming: List<ChangeRequest>,
        outgoing: List<ChangeRequest>,
        requestId: String
    ): Int {
        val incomingHeader = if (incoming.isEmpty()) 0 else 1
        val inIncoming = incoming.indexOfFirst { it.id == requestId }
        if (inIncoming >= 0) return incomingHeader + inIncoming

        val inOutgoing = outgoing.indexOfFirst { it.id == requestId }
        if (inOutgoing < 0) return -1
        val outgoingHeader = 1
        return incomingHeader + incoming.size + outgoingHeader + inOutgoing
    }
}
