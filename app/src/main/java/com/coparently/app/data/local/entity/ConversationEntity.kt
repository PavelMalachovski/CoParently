package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing a conversation in the local Room database.
 *
 * Read and delivery state live here as two `{uid: epochMillis}` JSON maps rather than a
 * stored unread counter — the counter that used to live on this row was never incremented
 * by anything, so the unread count and read/delivered ticks are derived from these marks
 * instead (see `com.coparently.app.domain.chat.ChatReadState`).
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val participantsJson: String, // JSON array of user IDs
    val title: String,
    val lastMessageId: String? = null, // Reference to the last message
    /** `{uid: epochMillis}` as JSON — when each participant last opened the thread. */
    val lastReadAtJson: String = "{}",
    /** `{uid: epochMillis}` as JSON — when each participant's device last ingested messages. */
    val lastDeliveredAtJson: String = "{}",
    /** Timestamp of the newest message, for ordering. */
    val lastMessageAtMillis: Long? = null,
    /** Set on a legacy conversation once its messages have been merged into the canonical one. */
    val archived: Boolean = false,
    val createdAt: LocalDateTime,
    val syncedToFirestore: Boolean = false
)
