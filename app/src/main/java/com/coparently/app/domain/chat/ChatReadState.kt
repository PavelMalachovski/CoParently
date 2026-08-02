package com.coparently.app.domain.chat

import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import java.time.ZoneId

/**
 * Derives everything the chat UI needs from the conversation's two per-user marks:
 * how many messages are unread, and how far each of my own messages has got.
 *
 * Nothing here is stored. One `lastReadAt` write when the thread opens and one
 * `lastDeliveredAt` write when a batch arrives are enough to render both.
 */
object ChatReadState {

    /**
     * Number of messages the other parent sent after [lastReadAtMillis].
     *
     * A message whose timestamp equals the mark is read — the mark is written *after*
     * the messages it covers. My own messages never count.
     *
     * @param lastReadAtMillis My mark, or null when I have never opened the thread.
     */
    fun unreadCount(messages: List<Message>, myUid: String, lastReadAtMillis: Long?): Int {
        val mark = lastReadAtMillis ?: Long.MIN_VALUE
        return messages.count { it.senderId != myUid && it.epochMillis() > mark }
    }

    /**
     * The status to render for [message], promoting a successfully sent message to
     * [MessageSendStatus.DELIVERED] or [MessageSendStatus.READ] according to the other
     * parent's marks.
     *
     * Only a [MessageSendStatus.SENT] message is ever promoted: something still sending,
     * or failed, has not reached the server, so no mark can say anything about it.
     */
    fun statusFor(
        message: Message,
        otherUid: String,
        lastReadAt: Map<String, Long>,
        lastDeliveredAt: Map<String, Long>
    ): MessageSendStatus {
        if (message.status != MessageSendStatus.SENT) return message.status
        val sentAt = message.epochMillis()
        return when {
            (lastReadAt[otherUid] ?: Long.MIN_VALUE) >= sentAt -> MessageSendStatus.READ
            (lastDeliveredAt[otherUid] ?: Long.MIN_VALUE) >= sentAt -> MessageSendStatus.DELIVERED
            else -> MessageSendStatus.SENT
        }
    }

    /**
     * The message's timestamp as epoch millis.
     *
     * `Message.timestamp` is a `LocalDateTime`, so it needs a zone to become an instant.
     * The device zone is correct here: both marks are written by clients using the same
     * conversion, and the comparison is between values produced the same way.
     */
    private fun Message.epochMillis(): Long =
        timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
