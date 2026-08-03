package com.coparently.app.domain.model

import java.time.LocalDateTime

/**
 * Domain model representing a conversation between parents.
 * This is the clean architecture model used in the domain layer.
 *
 * @property id Unique identifier for the conversation
 * @property participants List of Firebase UIDs of participants
 * @property title Title/name of the conversation
 * @property lastMessage The most recent message in the conversation
 * @property lastReadAt `{uid: epochMillis}` — when each participant last opened the thread.
 *   `unreadCount` was removed in favour of this: nothing ever incremented the old stored
 *   counter, and the unread count is now derived from this mark by
 *   `com.coparently.app.domain.chat.ChatReadState.unreadCount`.
 * @property lastDeliveredAt `{uid: epochMillis}` — when each participant's device last
 *   ingested messages. Used with [lastReadAt] to derive the sent/delivered/read ticks via
 *   `com.coparently.app.domain.chat.ChatReadState.statusFor`.
 * @property lastMessageAtMillis Timestamp of the newest message, for ordering.
 * @property archived Set on a legacy conversation once its messages have been merged into
 *   the canonical one.
 * @property createdAt When the conversation was created
 * @property syncedToFirestore Whether the conversation has been synced to Firestore
 */
data class Conversation(
    val id: String,
    val participants: List<String>,
    val title: String,
    val lastMessage: Message? = null,
    val lastReadAt: Map<String, Long> = emptyMap(),
    val lastDeliveredAt: Map<String, Long> = emptyMap(),
    val lastMessageAtMillis: Long? = null,
    val archived: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val syncedToFirestore: Boolean = false
)
