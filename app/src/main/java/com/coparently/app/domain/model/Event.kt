package com.coparently.app.domain.model

import com.coparently.app.domain.events.EventAcceptance
import com.coparently.app.domain.family.FamilyMemberRef
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
 * @property sharedWith List of Firebase UIDs that this event is shared with
 * @property lastModifiedBy Firebase UID of the user who last modified this event
 * @property permissions Permission level for the event (read_only, read_write)
 * @property isPrivate Whether the event is visible only to its creator (never synced to the co-parent)
 * @property recurrenceEndDate Optional last date (inclusive) for recurring event expansion
 * @property pickupConfirmedBy Parent who confirmed the pickup ("mom" or "dad"), null if not confirmed
 * @property pickupConfirmedAt Timestamp when the pickup was confirmed
 * @property reminderMinutes Minutes before start to show a reminder notification (null = no reminder)
 * @property imageUrl Optional download URL of a photo attached to the event (shared with the co-parent)
 * @property acceptance Whether the other parent still has to agree to this event before it counts.
 * Deliberately not [pickupConfirmedBy], which records a parent collecting the child after the
 * fact; see [com.coparently.app.domain.events.EventAcceptance].
 * @property acceptedBy Firebase UID of whoever answered, or null while unanswered
 * @property acceptedAt Timestamp of that answer, or null while unanswered
 * @property isImportant Whether the co-parent is **expected** at this event. Set when the event
 * is created and rendered as an exclamation mark beside the title. Deliberately a statement of
 * expectation and not an obligation the app enforces: nothing blocks saving and nothing chases
 * the other parent. Distinct from [acceptance], which asks the co-parent a question they must
 * answer before the event counts at all — this one only says what the event means.
 * @property forMembers The children and pets this event is about. Empty means the whole family,
 * which is what every event created before the reference type existed is — see [FamilyMemberRef].
 * Deliberately a different question from [parentOwner]: that is a custody slot, and whose *day*
 * an event falls on does not change because it is one child's dentist appointment and not the
 * other's.
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
    val createdByFirebaseUid: String? = null,
    val sharedWith: List<String> = emptyList(), // Firebase UIDs
    val lastModifiedBy: String? = null,
    val permissions: String = "read_write", // "read_only" or "read_write"
    val isPrivate: Boolean = false,
    val recurrenceEndDate: java.time.LocalDate? = null,
    val pickupConfirmedBy: String? = null,
    val pickupConfirmedAt: LocalDateTime? = null,
    val reminderMinutes: Int? = null,
    val imageUrl: String? = null,
    val acceptance: EventAcceptance = EventAcceptance.NOT_REQUIRED,
    val acceptedBy: String? = null,
    val acceptedAt: LocalDateTime? = null,
    val isImportant: Boolean = false,
    val forMembers: List<FamilyMemberRef> = emptyList(),
    /**
     * The UID of the calendar friend expected at this event, or null when none is.
     *
     * A friend is not an owner: [parentOwner] stays one of the two slots, because whose *day*
     * this falls on is a fact about custody and does not change because a grandmother is doing
     * the pickup. This only records that the friend takes part, which is what the calendar's
     * friend filter shows and what the grid marks in the friend's colour.
     */
    val friendParticipates: String? = null
)

