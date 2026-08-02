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
     * `Message.timestamp` is a naive `LocalDateTime` — it was written as `LocalDateTime.now()`
     * on the sending device and carries no offset — so turning it into an instant needs a zone,
     * and the only one available here is the *reading* device's own [ZoneId.systemDefault].
     *
     * That is only correct while both parents' devices share a timezone. If they don't, a
     * message's wall-clock time gets reinterpreted in the wrong zone: a message written at
     * 14:00 in UTC+2 is read back as 14:00 in a UTC+0 reader's zone, i.e. as having been sent
     * two hours later than it actually was. Relative to that reader's own `lastReadAt`/
     * `lastDeliveredAt` marks (real epoch millis, advanced as the reader acts *now*), the
     * message can permanently appear to be from the future: [unreadCount] never counts it as
     * read, its badge never clears, and [statusFor] never promotes it past `SENT` — the ticks
     * stay stuck.
     *
     * The real fix is storing `Message.timestamp` as epoch millis instead of a naive
     * `LocalDateTime`, removing the need for this conversion entirely. That is a larger,
     * separate change; today's behaviour is accepted as-is because both parents are expected
     * to be in the same timezone.
     */
    private fun Message.epochMillis(): Long =
        timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
