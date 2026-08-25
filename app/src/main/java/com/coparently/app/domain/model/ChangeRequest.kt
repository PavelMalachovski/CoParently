package com.coparently.app.domain.model

import java.time.LocalDateTime

/**
 * Domain model for a proposed change to an event, sent by one parent to the other.
 *
 * The requester proposes a new start (and optionally end) time for an existing shared
 * event; the other parent can accept (the event is updated) or decline. Requests are
 * synced through Firestore so both parents see their state.
 *
 * @property id Unique identifier of the request
 * @property eventId Id of the event the change is proposed for
 * @property eventTitle Denormalized event title so dashboards can render without a join
 * @property requestedBy Firebase UID of the parent who proposed the change
 * @property requestedTo Firebase UID of the parent who must respond
 * @property currentStartDateTime Event start at the moment the request was created
 * @property currentEndDateTime Event end at the moment the request was created
 * @property proposedStartDateTime Proposed new start
 * @property proposedEndDateTime Proposed new end (null keeps the event open-ended)
 * @property note Optional free-text explanation from the requester
 * @property status Current lifecycle state of the request
 * @property createdAt When the request was created
 * @property respondedAt When the request was accepted/declined/cancelled
 * @property syncedToFirestore Whether the request has been synced to Firestore
 */
data class ChangeRequest(
    val id: String,
    val eventId: String,
    val eventTitle: String,
    val requestedBy: String,
    val requestedTo: String,
    val currentStartDateTime: LocalDateTime,
    val currentEndDateTime: LocalDateTime? = null,
    val proposedStartDateTime: LocalDateTime,
    val proposedEndDateTime: LocalDateTime? = null,
    val note: String? = null,
    val status: ChangeRequestStatus = ChangeRequestStatus.PENDING,
    val createdAt: LocalDateTime,
    val respondedAt: LocalDateTime? = null,
    val syncedToFirestore: Boolean = false,
    /**
     * The co-parenting relationship this record belongs to, or null while it belongs to nobody
     * but its creator. See [com.coparently.app.domain.model.Event.familyId].
     */
    val familyId: String? = null
)

/**
 * Lifecycle state of a [ChangeRequest].
 */
enum class ChangeRequestStatus {
    /** Waiting for the other parent to respond. */
    PENDING,

    /** Accepted; the event has been updated to the proposed time. */
    ACCEPTED,

    /** Declined by the other parent; the event is unchanged. */
    DECLINED,

    /** Withdrawn by the requester before a response. */
    CANCELLED;

    val displayName: String
        get() = when (this) {
            PENDING -> "Pending"
            ACCEPTED -> "Accepted"
            DECLINED -> "Declined"
            CANCELLED -> "Cancelled"
        }
}
