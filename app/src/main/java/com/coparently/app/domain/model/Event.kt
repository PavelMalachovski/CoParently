package com.coparently.app.domain.model

import java.time.LocalDateTime

/**
 * Domain model representing an event.
 * This is the clean architecture model used in the domain layer.
 *
 * @property id Unique identifier for the event
 * @property title Title of the event
 * @property description Optional description of the event
 * @property startDateTime Start date and time of the event
 * @property endDateTime Optional end date and time of the event
 * @property eventType Type of the event (e.g., "mom", "dad", "training", "doctor")
 * @property parentOwner Parent who owns this event ("mom" or "dad")
 * @property isRecurring Whether the event is recurring
 * @property recurrencePattern Pattern for recurring events (e.g., "daily", "weekly", "monthly")
 * @property createdAt Timestamp when the event was created
 * @property updatedAt Timestamp when the event was last updated
 * @property syncedToFirestore Whether the event has been synced to Firestore
 * @property createdByFirebaseUid Firebase UID of the user who created this event
 */
data class Event(
    val id: String,
    val title: String,
    val description: String? = null,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime? = null,
    val eventType: String,
    val parentOwner: String, // "mom" or "dad"
    val isRecurring: Boolean = false,
    val recurrencePattern: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val syncedToFirestore: Boolean = false,
    val createdByFirebaseUid: String? = null
)

