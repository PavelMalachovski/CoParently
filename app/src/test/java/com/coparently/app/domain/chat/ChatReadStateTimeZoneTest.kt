package com.coparently.app.domain.chat

import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The two parents are not always in the same timezone, and read state must not care.
 *
 * Every scenario here is the same shape: a message is created the way the *sending* device
 * creates it — with that device's default zone in force — and then read state is evaluated
 * the way the *receiving* device evaluates it, with a different default zone in force. The
 * marks are true epoch millis throughout, because that is what they are: a mark is written
 * by one device and compared on the other, so the only thing both can agree on is an instant.
 *
 * The message's own send time is deliberately the model's default rather than a value these
 * tests supply. The default is what production uses (`Message(...)` with no explicit time),
 * and whether it survives a change of zone is precisely what is under test.
 */
class ChatReadStateTimeZoneTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun captureDefaultZone() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalZone)
    }

    /**
     * Guards the premise of every other test here: the scenario is only cross-zone while the
     * two zones actually differ. Making them equal would turn the suite green without the
     * defect being fixed.
     */
    @Test
    fun `the sending and receiving zones really are different`() {
        val now = Instant.now()

        assertNotEquals(
            ZONE_AHEAD.rules.getOffset(now),
            ZONE_BEHIND.rules.getOffset(now)
        )
    }

    /**
     * Sender ahead of reader. The reader has read the thread, so their mark is at or past the
     * instant the message was sent and nothing may still be counted unread.
     *
     * Reinterpreting the sender's wall clock in the reader's zone lands it
     * [ZONE_DIFFERENCE_MILLIS] in the reader's own future, where no mark the reader can ever
     * write reaches it: the badge never clears.
     */
    @Test
    fun `a read message does not stay unread when the sender's zone is ahead`() {
        val sent = messageSentIn(ZONE_AHEAD, sender = THEM)

        val unread = inZone(ZONE_BEHIND) {
            ChatReadState.unreadCount(
                messages = listOf(sent.message),
                myUid = ME,
                lastReadAtMillis = sent.atOrAfterSending
            )
        }

        assertEquals(
            "the reader is ${ZONE_DIFFERENCE_MILLIS}ms behind the sender",
            0,
            unread
        )
    }

    /**
     * Reader ahead of sender — the same skew, the opposite damage. The reader's mark predates
     * the message, so it is genuinely unread; reinterpreting the wall clock drags the message
     * [ZONE_DIFFERENCE_MILLIS] into the reader's past, behind their mark, and it is silently
     * counted as already read.
     */
    @Test
    fun `an unread message is not counted as read when the reader's zone is ahead`() {
        val sent = messageSentIn(ZONE_BEHIND, sender = THEM)

        val unread = inZone(ZONE_AHEAD) {
            ChatReadState.unreadCount(
                messages = listOf(sent.message),
                myUid = ME,
                lastReadAtMillis = sent.beforeSending
            )
        }

        assertEquals(
            "the reader is ${ZONE_DIFFERENCE_MILLIS}ms ahead of the sender",
            1,
            unread
        )
    }

    /**
     * The other parent's read mark, written on their device against the true send instant,
     * must promote my own message to [MessageSendStatus.READ] on mine — whichever of the two
     * zones each of us is in.
     */
    @Test
    fun `my own message is promoted to READ by the other parent's mark across zones`() {
        val sent = messageSentIn(ZONE_AHEAD, sender = ME)

        val status = inZone(ZONE_BEHIND) {
            ChatReadState.statusFor(
                message = sent.message,
                otherUid = THEM,
                lastReadAt = mapOf(THEM to sent.atOrAfterSending),
                lastDeliveredAt = emptyMap()
            )
        }

        assertEquals(MessageSendStatus.READ, status)
    }

    /**
     * The converse: a mark that predates the message must leave it [MessageSendStatus.SENT].
     * A zone skew in this direction fabricates a read receipt for a message the other parent
     * has not seen — worse than a stuck tick, because it is silently wrong.
     */
    @Test
    fun `a mark older than my message never promotes it across zones`() {
        val sent = messageSentIn(ZONE_BEHIND, sender = ME)

        val status = inZone(ZONE_AHEAD) {
            ChatReadState.statusFor(
                message = sent.message,
                otherUid = THEM,
                lastReadAt = mapOf(THEM to sent.beforeSending),
                lastDeliveredAt = emptyMap()
            )
        }

        assertEquals(MessageSendStatus.SENT, status)
    }

    /**
     * The same message, evaluated in two zones, must be believed to have been sent at the same
     * instant. This is the defect stated as a number rather than as a symptom: when it fails,
     * the two values differ by exactly the offset between the zones.
     */
    @Test
    fun `the instant a message is believed to have been sent does not move with the zone`() {
        val sent = messageSentIn(ZONE_AHEAD, sender = THEM)

        val asTheSenderSeesIt = inZone(ZONE_AHEAD) { readThreshold(sent) }
        val asTheReaderSeesIt = inZone(ZONE_BEHIND) { readThreshold(sent) }

        assertEquals(asTheSenderSeesIt, asTheReaderSeesIt)
    }

    // ---- scenario construction --------------------------------------------

    /**
     * A message and the instants either side of its creation.
     *
     * @property message The message, carrying whatever send time the model defaulted to.
     * @property beforeSending An instant strictly before the message was sent — a mark here
     *   means "not read".
     * @property atOrAfterSending An instant at or after the message was sent — a mark here
     *   means "read".
     */
    private class SentMessage(
        val message: Message,
        val beforeSending: Long,
        val atOrAfterSending: Long
    )

    /**
     * A message as a device in [zone] creates it: that device's own default zone is in force
     * for the whole construction, exactly as it is on the sending phone.
     *
     * The bracketing instants come from [System.currentTimeMillis], which is the same clock in
     * every zone, so they describe when the message was really sent regardless of where.
     */
    private fun messageSentIn(zone: ZoneId, sender: String): SentMessage = inZone(zone) {
        // Strictly before: sharing a millisecond with the message would make this "equal to
        // the send instant", which counts as read.
        val before = System.currentTimeMillis() - 1
        val message = Message(
            id = "message-1",
            conversationId = "conversation-1",
            senderId = sender,
            senderName = sender,
            content = "See you at 5",
            status = MessageSendStatus.SENT
        )
        SentMessage(message, before, System.currentTimeMillis())
    }

    /**
     * The lowest mark for which [sent] counts as read, in whatever zone is currently default.
     *
     * A mark equal to a message's send instant counts as read, so this threshold *is* the
     * instant the code under test believes the message was sent — recovered by bisection
     * rather than by repeating the conversion that produces it, which would make the test
     * agree with the implementation instead of with reality.
     */
    private fun readThreshold(sent: SentMessage): Long {
        val messages = listOf(sent.message)
        fun isRead(mark: Long) =
            ChatReadState.unreadCount(messages, myUid = ME, lastReadAtMillis = mark) == 0

        var unreadMark = sent.beforeSending - SEARCH_RADIUS_MILLIS
        var readMark = sent.atOrAfterSending + SEARCH_RADIUS_MILLIS
        assertTrue("a mark two days before the message must not count as read", !isRead(unreadMark))
        assertTrue("a mark two days after the message must count as read", isRead(readMark))

        while (unreadMark + 1 < readMark) {
            val mid = unreadMark + (readMark - unreadMark) / 2
            if (isRead(mid)) readMark = mid else unreadMark = mid
        }
        return readMark
    }

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
        const val ME = "uidA"
        const val THEM = "uidB"

        /** Fixed offsets rather than named zones, so no DST transition can blur the scenario. */
        val ZONE_AHEAD: ZoneId = ZoneOffset.ofHours(2)
        val ZONE_BEHIND: ZoneId = ZoneOffset.UTC

        const val ZONE_DIFFERENCE_MILLIS = 2L * 60 * 60 * 1000

        /** Two days: wide enough to bracket any plausible zone offset, including 14 hours. */
        const val SEARCH_RADIUS_MILLIS = 2L * 24 * 60 * 60 * 1000
    }
}
