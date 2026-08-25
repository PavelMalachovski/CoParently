package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing an event in the local Room database.
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
 * @property sharedWithJson JSON string of Firebase UIDs that this event is shared with
 * @property lastModifiedBy Firebase UID of the user who last modified this event
 * @property permissions Permission level for the event (read_only, read_write)
 * @property isPrivate Whether the event is visible only to its creator (never synced to the co-parent)
 * @property recurrenceEndDate Optional last date (inclusive, ISO string) for recurring event expansion
 * @property pickupConfirmedBy Parent who confirmed the pickup ("mom" or "dad"), null if not confirmed
 * @property pickupConfirmedAt Timestamp when the pickup was confirmed
 * @property reminderMinutes Minutes before start to show a reminder notification (null = no reminder)
 * @property deletedAtMillis When the event was deleted (epoch millis), or null while it is alive
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey
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
    val sharedWithJson: String = "[]", // JSON array of Firebase UIDs
    val lastModifiedBy: String? = null,
    val permissions: String = "read_write",
    val isPrivate: Boolean = false,
    val recurrenceEndDate: java.time.LocalDate? = null,
    val pickupConfirmedBy: String? = null,
    val pickupConfirmedAt: LocalDateTime? = null,
    val reminderMinutes: Int? = null,
    val imageUrl: String? = null,
    /**
     * Whether the other parent still has to agree to this event, as
     * [com.coparently.app.domain.events.EventAcceptance]'s name. Stored as a string rather than a
     * converted type, the same way every other status crosses this schema, and defaulted to
     * `NOT_REQUIRED` so every row that predates the column is correct without being rewritten.
     */
    val acceptance: String = "NOT_REQUIRED",
    /** Firebase UID of whoever answered, or null while unanswered. */
    val acceptedBy: String? = null,
    /** When they answered, or null while unanswered. */
    val acceptedAt: LocalDateTime? = null,
    /**
     * Whether the co-parent is expected at this event — see
     * [com.coparently.app.domain.model.Event.isImportant]. Defaulted to false so every row that
     * predates the column is correct without being rewritten, which is also the honest reading:
     * an event created before the flag existed was never marked.
     */
    val isImportant: Boolean = false,
    /**
     * Which calendar friend takes part — see
     * [com.coparently.app.domain.model.Event.friendParticipates]. Nullable, no default beyond
     * null: an event predating the column had no friend on it, which is what null says.
     */
    val friendParticipates: String? = null,
    /**
     * When this event was deleted, epoch millis — or null while it is alive, which every row
     * that predates the column is.
     *
     * A row with this set is a **pending tombstone**: the parent has deleted the event, every
     * query hides it, and it survives in Room only until the deletion has been written to
     * Firestore, at which point the row is removed for real. So it is not a soft delete in the
     * usual sense of "the data is still there" — it is an outbox entry, and the reason it is one
     * is that the previous delete had nowhere to fail *to*. A remote delete that was rejected
     * (offline, or a rule that refuses) was logged and dropped, the document survived, and the
     * next sync pulled the event back onto the phone that had just deleted it. A row nothing
     * retries is a delete that undoes itself.
     *
     * Epoch millis rather than a `LocalDateTime` because this value crosses to the co-parent's
     * phone, which may be in another time zone — the same reasoning as `Message.sentAtMillis`,
     * and deliberately not the reasoning behind [startDateTime], where a naive local time is
     * the right model for when a handover happens.
     */
    /**
     * Who this event is about — a JSON array of `FamilyMemberRef` stored strings.
     *
     * `[]` means the whole family, which is what every row written before schema 28 holds. Not
     * the same question as [parentOwner], which is the custody slot whose day it falls on.
     */
    val forMembersJson: String = "[]",
    val deletedAtMillis: Long? = null
)

