package com.coparently.app.domain.model

/**
 * Domain model representing a message in a conversation.
 * This is the clean architecture model used in the domain layer.
 *
 * @property id Unique identifier for the message
 * @property conversationId ID of the conversation this message belongs to
 * @property senderId Firebase UID of the sender
 * @property senderName Display name of the sender
 * @property content Text content of the message
 * @property sentAtMillis When the message was sent, as milliseconds since the epoch (UTC).
 *   An instant, not a wall clock: the two parents' devices need not share a timezone, and the
 *   read/delivered marks it is compared against are epoch millis too. The UI formats it in the
 *   reading device's own zone, which is what a reader wants to see.
 * @property messageType Type of message (TEXT, IMAGE, VOICE, EVENT_LINK)
 * @property attachments List of attachment URLs
 * @property isRead Whether the message has been read by the recipient
 * @property replyToMessageId Optional ID of the message being replied to
 * @property syncedToFirestore Whether the message has been synced to Firestore
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val sentAtMillis: Long = System.currentTimeMillis(),
    val messageType: MessageType = MessageType.TEXT,
    val attachments: List<String> = emptyList(),
    val isRead: Boolean = false,
    val replyToMessageId: String? = null,
    val syncedToFirestore: Boolean = false,
    val status: MessageSendStatus = MessageSendStatus.SENT
)

/**
 * Types of messages supported in the chat system.
 */
enum class MessageType {
    TEXT,
    IMAGE,
    VOICE,
    EVENT_LINK
}

/**
 * How far a message has got.
 *
 * Only [SENDING], [SENT] and [ERROR] are ever persisted — they describe this device's own
 * write attempt. [DELIVERED] and [READ] are derived at render time by
 * [com.coparently.app.domain.chat.ChatReadState.statusFor] from the conversation's
 * per-user marks, and must never be written to the message row.
 */
enum class MessageSendStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    ERROR
}
