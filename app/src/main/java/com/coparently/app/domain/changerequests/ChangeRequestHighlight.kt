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
     * @param precedingItems Items rendered *above* the incoming header — the day-swap section,
     *   which is header plus one card per swap, or zero when there are none. It is a count rather
     *   than the list itself because nothing here needs to look inside it: this function is only
     *   ever asked about event change requests, since the highlight arrives from a chat card
     *   about an event. Getting it wrong scrolls the inbox to the wrong card, which is silent.
     * @return The item index, or -1 when the request is in neither section.
     */
    fun indexInInbox(
        incoming: List<ChangeRequest>,
        outgoing: List<ChangeRequest>,
        requestId: String,
        precedingItems: Int = 0
    ): Int {
        val incomingHeader = precedingItems + if (incoming.isEmpty()) 0 else 1
        val inIncoming = incoming.indexOfFirst { it.id == requestId }
        if (inIncoming >= 0) return incomingHeader + inIncoming

        val inOutgoing = outgoing.indexOfFirst { it.id == requestId }
        if (inOutgoing < 0) return -1
        val outgoingHeader = 1
        return incomingHeader + incoming.size + outgoingHeader + inOutgoing
    }
}
