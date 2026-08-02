package com.coparently.app.data.repository

import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * What a message looks like on the wire, in both formats.
 *
 * A message document's `timestamp` used to be a naive ISO local date-time string with no
 * offset — two devices in different timezones could not agree on what instant it named. It is
 * now a number: epoch millis, the same unit as the read/delivered marks.
 *
 * Both formats have to be readable for as long as either exists in the wild: the owner has real
 * documents written in the old format, and the co-parent's phone keeps writing them until it
 * takes this build. Only the new one is ever written.
 */
class ChatMappersWireFormatTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun captureDefaultZone() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `a message is written with its send instant as a number`() {
        val document = message().toFirestoreMap()

        assertEquals(SENT_AT_MILLIS, document["timestamp"])
    }

    @Test
    fun `a numeric timestamp is read back unchanged, whatever zone the reader is in`() {
        val document = message().toFirestoreMap()

        val inUtc = inZone(ZoneOffset.UTC) { document.toMessageOrNull() }
        val inKyiv = inZone(ZoneOffset.ofHours(3)) { document.toMessageOrNull() }

        assertEquals(SENT_AT_MILLIS, inUtc?.sentAtMillis)
        assertEquals(
            "an instant on the wire is the same instant in every zone",
            inUtc?.sentAtMillis,
            inKyiv?.sentAtMillis
        )
    }

    @Test
    fun `a legacy string timestamp is read as local time in the reading device's zone`() {
        val document = legacyDocument("2026-08-01T12:00:00")

        val message = inZone(ZoneOffset.ofHours(2)) { document.toMessageOrNull() }

        // 12:00 at UTC+2 is 10:00 UTC. This is the best available reading of a value that never
        // recorded where it was written, and the one the app applied to it until now.
        assertEquals(SENT_AT_MILLIS, message?.sentAtMillis)
    }

    @Test
    fun `a document whose timestamp is neither a number nor a date is skipped`() {
        assertNull(legacyDocument("not-a-date").toMessageOrNull())
        assertNull(message().toFirestoreMap().minus("timestamp").toMessageOrNull())
        assertNull((message().toFirestoreMap() + ("timestamp" to true)).toMessageOrNull())
    }

    @Test
    fun `everything else about a message survives the round trip`() {
        val original = message()

        val restored = original.toFirestoreMap().toMessageOrNull()

        assertEquals(original.id, restored?.id)
        assertEquals(original.conversationId, restored?.conversationId)
        assertEquals(original.senderId, restored?.senderId)
        assertEquals(original.senderName, restored?.senderName)
        assertEquals(original.content, restored?.content)
        assertEquals(original.messageType, restored?.messageType)
        assertEquals(original.attachments, restored?.attachments)
        assertEquals(original.replyToMessageId, restored?.replyToMessageId)
    }

    @Test
    fun `a message survives the Room round trip`() {
        val original = message()

        assertEquals(original, original.toEntity().toDomain())
    }

    private fun message() = Message(
        id = "message-1",
        conversationId = "uidA__uidB",
        senderId = "uidA",
        senderName = "Anna",
        content = "See you at 5",
        sentAtMillis = SENT_AT_MILLIS,
        messageType = MessageType.TEXT,
        attachments = listOf("event-1"),
        replyToMessageId = "message-0",
        status = MessageSendStatus.SENT
    )

    /** A message document in the format written before send times became instants. */
    private fun legacyDocument(timestamp: String): Map<String, Any> =
        message().toFirestoreMap() + ("timestamp" to timestamp)

    /** Runs [block] with [zone] as the JVM's default, restoring the previous one afterwards. */
    private fun <T> inZone(zone: ZoneId, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private companion object {
        /** 2026-08-01T10:00:00Z, which is 12:00 at UTC+2. */
        const val SENT_AT_MILLIS = 1_785_578_400_000L
    }
}
