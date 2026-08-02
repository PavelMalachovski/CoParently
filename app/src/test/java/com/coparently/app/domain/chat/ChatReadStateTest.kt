package com.coparently.app.domain.chat

import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatReadStateTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(millis: Long) = LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(millis),
        zone
    )

    private fun message(id: String, sender: String, millis: Long) = Message(
        id = id,
        conversationId = "c1",
        senderId = sender,
        senderName = sender,
        content = "hello",
        timestamp = at(millis),
        status = MessageSendStatus.SENT
    )

    @Test
    fun `messages from the other parent after the read mark are unread`() {
        val messages = listOf(message("m1", "them", 200), message("m2", "them", 300))

        assertEquals(2, ChatReadState.unreadCount(messages, myUid = "me", lastReadAtMillis = 100))
    }

    @Test
    fun `my own messages are never unread`() {
        val messages = listOf(message("m1", "me", 200), message("m2", "them", 300))

        assertEquals(1, ChatReadState.unreadCount(messages, myUid = "me", lastReadAtMillis = 100))
    }

    @Test
    fun `a message exactly at the read mark counts as read`() {
        val messages = listOf(message("m1", "them", 100))

        assertEquals(0, ChatReadState.unreadCount(messages, myUid = "me", lastReadAtMillis = 100))
    }

    @Test
    fun `everything is unread when there is no read mark yet`() {
        val messages = listOf(message("m1", "them", 1), message("m2", "them", 2))

        assertEquals(2, ChatReadState.unreadCount(messages, myUid = "me", lastReadAtMillis = null))
    }

    @Test
    fun `an empty message list has no unread messages`() {
        assertEquals(0, ChatReadState.unreadCount(emptyList(), myUid = "me", lastReadAtMillis = null))
    }

    @Test
    fun `a read mark later than every message leaves nothing unread`() {
        val messages = listOf(message("m1", "them", 100), message("m2", "them", 200))

        assertEquals(0, ChatReadState.unreadCount(messages, myUid = "me", lastReadAtMillis = 1_000))
    }

    @Test
    fun `a sent message the other device has not fetched stays SENT`() {
        val status = ChatReadState.statusFor(
            message = message("m1", "me", 200),
            otherUid = "them",
            lastReadAt = emptyMap(),
            lastDeliveredAt = mapOf("them" to 100L)
        )

        assertEquals(MessageSendStatus.SENT, status)
    }

    @Test
    fun `a fetched but unopened message is DELIVERED`() {
        val status = ChatReadState.statusFor(
            message = message("m1", "me", 200),
            otherUid = "them",
            lastReadAt = mapOf("them" to 100L),
            lastDeliveredAt = mapOf("them" to 300L)
        )

        assertEquals(MessageSendStatus.DELIVERED, status)
    }

    @Test
    fun `an opened message is READ`() {
        val status = ChatReadState.statusFor(
            message = message("m1", "me", 200),
            otherUid = "them",
            lastReadAt = mapOf("them" to 300L),
            lastDeliveredAt = mapOf("them" to 300L)
        )

        assertEquals(MessageSendStatus.READ, status)
    }

    @Test
    fun `a mark exactly at the message timestamp counts`() {
        val status = ChatReadState.statusFor(
            message = message("m1", "me", 200),
            otherUid = "them",
            lastReadAt = mapOf("them" to 200L),
            lastDeliveredAt = mapOf("them" to 200L)
        )

        assertEquals(MessageSendStatus.READ, status)
    }

    @Test
    fun `a failed message is never promoted by the marks`() {
        val failed = message("m1", "me", 200).copy(status = MessageSendStatus.ERROR)

        val status = ChatReadState.statusFor(
            message = failed,
            otherUid = "them",
            lastReadAt = mapOf("them" to 300L),
            lastDeliveredAt = mapOf("them" to 300L)
        )

        assertEquals(MessageSendStatus.ERROR, status)
    }

    @Test
    fun `a conversation nobody has touched leaves the message at SENT`() {
        val status = ChatReadState.statusFor(
            message = message("m1", "me", 200),
            otherUid = "them",
            lastReadAt = emptyMap(),
            lastDeliveredAt = emptyMap()
        )

        assertEquals(MessageSendStatus.SENT, status)
    }

    @Test
    fun `an unsent message is never promoted by the marks`() {
        val sending = message("m1", "me", 200).copy(status = MessageSendStatus.SENDING)

        val status = ChatReadState.statusFor(
            message = sending,
            otherUid = "them",
            lastReadAt = mapOf("them" to 300L),
            lastDeliveredAt = mapOf("them" to 300L)
        )

        assertEquals(MessageSendStatus.SENDING, status)
    }
}
