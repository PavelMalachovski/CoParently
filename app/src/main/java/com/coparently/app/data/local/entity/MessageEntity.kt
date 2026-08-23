package com.coparently.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a message in the local Room database.
 *
 * @property sentAtMillis When the message was sent, as milliseconds since the epoch (UTC) —
 *   an instant, so a thread stays correct when the two parents are in different timezones.
 *   Replaced the naive `timestamp` wall-clock string in schema 13; see
 *   `DatabaseMigrations.MIGRATION_12_13`.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val sentAtMillis: Long,
    val messageType: String, // Stored as string (TEXT, IMAGE, etc.)
    val attachmentsJson: String = "[]", // JSON array of URLs
    val isRead: Boolean = false,
    val replyToMessageId: String? = null,
    val syncedToFirestore: Boolean = false,
    @ColumnInfo(defaultValue = "SENT")
    val status: String? = null, // Stored as string (SENDING, SENT, ERROR), nullable for migration compatibility
    /**
     * JSON object of [com.coparently.app.domain.activity.ActivityAnnouncement] for an `ACTIVITY`
     * message, null for every other kind. One column rather than a table: it is read and written
     * whole, exactly like `attachmentsJson` beside it.
     */
    val activityJson: String? = null
)
