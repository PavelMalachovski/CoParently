package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageType
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for the read/delivery marks and the deterministic conversation the chat
 * rework is built on.
 *
 * Two subjects, because the two halves of the contract can only be asserted at different
 * levels. [repository] runs over a mocked [FirestoreMessageDataSource] and pins *behaviour*:
 * what lands in Room, what survives a remote failure, what is idempotent. [dataSource] is
 * the real object over a mocked [FirebaseFirestore] and pins the *wire format*: a mark has
 * to reach Firestore as a single dotted-path update (`lastReadAt.<uid>`), because the
 * deployed rules permit a participant to amend only their own key and reject a whole-map
 * write. Verifying "a write happened" would not catch that regression; verifying the field
 * path does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryReadStateTest {

    private lateinit var messageDao: MessageDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreMessageDataSource: FirestoreMessageDataSource
    private lateinit var repository: MessageRepositoryImpl

    private lateinit var firestore: FirebaseFirestore
    private lateinit var conversationsCollection: CollectionReference
    private lateinit var conversationDocument: DocumentReference
    private lateinit var messagesCollection: CollectionReference
    private lateinit var messageDocument: DocumentReference
    private lateinit var dataSource: FirestoreMessageDataSource

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        messageDao = mockk(relaxed = true)
        firebaseAuthService = mockk()
        firestoreMessageDataSource = mockk(relaxed = true)
        repository = MessageRepositoryImpl(messageDao, firebaseAuthService, firestoreMessageDataSource)

        val firebaseUser = mockk<FirebaseUser> { every { uid } returns UID_A }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { messageDao.getConversationById(any()) } returns null
        every { messageDao.getMessages(any(), any()) } returns flowOf(emptyList())
        every { messageDao.observeConversationById(any()) } returns flowOf(null)
        // A mark is the newest message's timestamp, so every mark test needs a thread.
        coEvery { messageDao.getMessagesOnce(any()) } returns listOf(messageRow(OLDER), messageRow(NEWEST))

        conversationDocument = mockk(relaxed = true)
        conversationsCollection = mockk(relaxed = true)
        messageDocument = mockk(relaxed = true)
        messagesCollection = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        every { firestore.collection("conversations") } returns conversationsCollection
        every { firestore.collection("messages") } returns messagesCollection
        every { conversationsCollection.document(any()) } returns conversationDocument
        every { messagesCollection.document(any()) } returns messageDocument
        every { conversationDocument.update(any<String>(), any()) } returns Tasks.forResult(null)
        every { conversationDocument.set(any(), any<SetOptions>()) } returns Tasks.forResult(null)
        every { messageDocument.set(any()) } returns Tasks.forResult(null)
        dataSource = FirestoreMessageDataSource(firestore)
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // ---- ensureConversation: one deterministic document ------------------

    @Test
    fun `ensureConversation derives the id from the sorted pair, whichever order it is asked in`() =
        runTest {
            val id = repository.ensureConversation(UID_B, UID_A, "Anna")

            assertEquals(ConversationKey.of(UID_A, UID_B), id)

            val documentId = slot<String>()
            val document = slot<Map<String, Any>>()
            coVerify(exactly = 1) {
                firestoreMessageDataSource.setConversation(capture(documentId), capture(document))
            }
            assertEquals(ConversationKey.of(UID_A, UID_B), documentId.captured)
            assertEquals(
                setOf(UID_A, UID_B),
                (document.captured["participants"] as List<*>).toSet()
            )
            assertEquals(false, document.captured["archived"])
        }

    @Test
    fun `ensureConversation twice targets the same single document`() = runTest {
        val first = repository.ensureConversation(UID_A, UID_B, "Anna")
        // The second call stands in for the other parent's device, or for a re-pairing:
        // it must land on the id the first one produced, not mint a second thread.
        val second = repository.ensureConversation(UID_B, UID_A, "Anna")

        assertEquals(first, second)
        val documentIds = mutableListOf<String>()
        coVerify { firestoreMessageDataSource.setConversation(capture(documentIds), any()) }
        assertEquals(setOf(first), documentIds.toSet())
    }

    @Test
    fun `the shared conversation document carries no title`() = runTest {
        val document = slot<Map<String, Any>>()

        repository.ensureConversation(UID_A, UID_B, "Anna")

        coVerify { firestoreMessageDataSource.setConversation(any(), capture(document)) }
        // `title` is this device's name for the *other* parent. Writing it to a document both
        // parents read made each of them relabel the other's thread with their own name, and
        // flip it back on the next open. It is derived locally instead.
        assertFalse(document.captured.containsKey("title"))
    }

    // ---- the marks: what reaches Room ------------------------------------

    @Test
    fun `markRead advances only this user's key in the local row`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow(
            lastReadAtJson = """{"$UID_B":500}"""
        )
        val stored = slot<ConversationEntity>()

        repository.markRead(CONVERSATION, UID_A)

        coVerify(exactly = 1) { messageDao.insertConversation(capture(stored)) }
        assertTrue(
            "the co-parent's own mark must survive",
            stored.captured.lastReadAtJson.contains("\"$UID_B\":500")
        )
        assertTrue(
            "this user's mark must be the newest message's timestamp",
            stored.captured.lastReadAtJson.contains("\"$UID_A\":$NEWEST")
        )
        assertEquals("delivery marks are untouched by markRead", "{}", stored.captured.lastDeliveredAtJson)
    }

    @Test
    fun `markDelivered advances only this user's delivery key`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        val stored = slot<ConversationEntity>()

        repository.markDelivered(CONVERSATION, UID_A)

        coVerify(exactly = 1) { messageDao.insertConversation(capture(stored)) }
        assertTrue(stored.captured.lastDeliveredAtJson.contains("\"$UID_A\""))
        assertEquals("read marks are untouched by markDelivered", "{}", stored.captured.lastReadAtJson)
    }

    @Test
    fun `a remote failure in markRead does not escape the repository`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        coEvery { firestoreMessageDataSource.markRead(any(), any(), any()) } throws
            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

        // Room is the source of truth: a refused remote write degrades to "the mark is local
        // for now, the next open retries", never to a crash out of the caller's coroutine.
        repository.markRead(CONVERSATION, UID_A)

        coVerify(exactly = 1) { messageDao.insertConversation(any()) }
    }

    @Test
    fun `the read mark is the newest message's timestamp, not the device clock`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        val remoteMark = slot<Long>()

        repository.markRead(CONVERSATION, UID_A)

        coVerify { firestoreMessageDataSource.markRead(CONVERSATION, UID_A, capture(remoteMark)) }
        assertEquals(NEWEST, remoteMark.captured)
        // A clock-derived mark reads ~now. That is what makes a device whose clock is briefly
        // set forward unrecoverable: the monotonic merge keeps the far-future mark for good
        // and that user's unread count is zero from then on. The thread cannot outrun itself.
        assertTrue(remoteMark.captured < System.currentTimeMillis())
    }

    @Test
    fun `the delivery mark comes from the same place as the read mark`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        val remoteMark = slot<Long>()

        repository.markDelivered(CONVERSATION, UID_A)

        coVerify { firestoreMessageDataSource.markDelivered(CONVERSATION, UID_A, capture(remoteMark)) }
        assertEquals(NEWEST, remoteMark.captured)
    }

    @Test
    fun `an empty thread records no mark at all`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        coEvery { messageDao.getMessagesOnce(CONVERSATION) } returns emptyList()

        repository.markRead(CONVERSATION, UID_A)
        repository.markDelivered(CONVERSATION, UID_A)

        // "Read up to here" says nothing when there is no here. A zero or a clock reading
        // would both be claims the thread cannot support.
        coVerify(exactly = 0) { messageDao.insertConversation(any()) }
        coVerify(exactly = 0) { firestoreMessageDataSource.markRead(any(), any(), any()) }
        coVerify(exactly = 0) { firestoreMessageDataSource.markDelivered(any(), any(), any()) }
    }

    // ---- the marks: what reaches Firestore -------------------------------

    @Test
    fun `markRead writes lastReadAt for this user and no other field`() = runTest {
        val field = slot<String>()
        val value = slot<Any>()

        dataSource.markRead(CONVERSATION, UID_A, AT_MILLIS)

        verify(exactly = 1) { conversationDocument.update(capture(field), capture(value)) }
        // The deployed rule permits amending your own key only; a whole-map write is denied.
        assertEquals("lastReadAt.$UID_A", field.captured)
        assertEquals(AT_MILLIS, value.captured)
    }

    @Test
    fun `markDelivered writes lastDeliveredAt for this user and no other field`() = runTest {
        val field = slot<String>()
        val value = slot<Any>()

        dataSource.markDelivered(CONVERSATION, UID_A, AT_MILLIS)

        verify(exactly = 1) { conversationDocument.update(capture(field), capture(value)) }
        assertEquals("lastDeliveredAt.$UID_A", field.captured)
        assertEquals(AT_MILLIS, value.captured)
    }

    @Test
    fun `setConversation merges rather than replacing, so existing marks survive`() = runTest {
        val options = slot<SetOptions>()

        dataSource.setConversation(CONVERSATION, mapOf("id" to CONVERSATION))

        // A plain `set` would drop both mark maps of whichever device happened to write last.
        verify(exactly = 1) { conversationDocument.set(any(), capture(options)) }
        assertNotNull(options.captured)
    }

    // ---- sendMessage ------------------------------------------------------

    @Test
    fun `sendMessage stores the message, bumps lastMessageAt and settles the row on SENT`() =
        runTest {
            coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
            val sentAt = slot<Long>()
            val messages = mutableListOf<MessageEntity>()
            val conversations = mutableListOf<ConversationEntity>()

            repository.sendMessage(message())

            coVerify(exactly = 1) { firestoreMessageDataSource.sendMessage(MESSAGE_ID, any()) }
            coVerify(exactly = 1) {
                firestoreMessageDataSource.bumpLastMessageAt(CONVERSATION, capture(sentAt))
            }
            assertEquals(SENT_AT_MILLIS, sentAt.captured)

            coVerify { messageDao.insertMessage(capture(messages)) }
            assertEquals(MessageSendStatus.SENT.name, messages.last().status)
            assertTrue(messages.last().syncedToFirestore)

            coVerify { messageDao.insertConversation(capture(conversations)) }
            assertEquals(SENT_AT_MILLIS, conversations.last().lastMessageAtMillis)
        }

    @Test
    fun `a failed send leaves the row ERROR and queued for the outbox`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        coEvery { firestoreMessageDataSource.sendMessage(any(), any()) } throws
            IllegalStateException("offline")
        val messages = mutableListOf<MessageEntity>()

        val thrown = runCatching { repository.sendMessage(message()) }.exceptionOrNull()

        assertNotNull("the caller still learns the send failed", thrown)
        coVerify { messageDao.insertMessage(capture(messages)) }
        assertEquals(MessageSendStatus.ERROR.name, messages.last().status)
        // The row stays unsynced, which is what `flushOutbox` selects on. Before the outbox
        // existed, ERROR was terminal and the message was never delivered at all.
        assertFalse("a failed send stays queued", messages.last().syncedToFirestore)
    }

    @Test
    fun `a refused send re-publishes the conversation and tries once more`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        var attempts = 0
        coEvery { firestoreMessageDataSource.sendMessage(any(), any()) } answers {
            attempts++
            if (attempts == 1) throw IllegalStateException("PERMISSION_DENIED") else Unit
        }
        val messages = mutableListOf<MessageEntity>()

        repository.sendMessage(message())

        coVerify(exactly = 1) { firestoreMessageDataSource.setConversation(CONVERSATION, any()) }
        coVerify { messageDao.insertMessage(capture(messages)) }
        assertEquals(MessageSendStatus.SENT.name, messages.last().status)
    }

    @Test
    fun `a failed lastMessageAt bump does not condemn a delivered message`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        coEvery { firestoreMessageDataSource.bumpLastMessageAt(any(), any()) } throws
            IllegalStateException("offline")
        val messages = mutableListOf<MessageEntity>()

        repository.sendMessage(message())

        coVerify { messageDao.insertMessage(capture(messages)) }
        assertEquals(MessageSendStatus.SENT.name, messages.last().status)
        assertTrue(messages.last().syncedToFirestore)
    }

    @Test
    fun `the outbox replays an unsent message and settles it`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        coEvery { messageDao.getUnsyncedConversations() } returns emptyList()
        coEvery { messageDao.getUnsyncedMessages() } returns listOf(unsentRow())
        val messages = mutableListOf<MessageEntity>()

        repository.flushOutbox()

        coVerify(exactly = 1) { firestoreMessageDataSource.sendMessage(MESSAGE_ID, any()) }
        coVerify { messageDao.insertMessage(capture(messages)) }
        assertEquals(MessageSendStatus.SENT.name, messages.last().status)
        assertTrue(messages.last().syncedToFirestore)
    }

    @Test
    fun `the outbox leaves the co-parent's own messages alone`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        coEvery { messageDao.getUnsyncedConversations() } returns emptyList()
        coEvery { messageDao.getUnsyncedMessages() } returns listOf(unsentRow(senderId = UID_B))

        repository.flushOutbox()

        coVerify(exactly = 0) { firestoreMessageDataSource.sendMessage(any(), any()) }
    }

    @Test
    fun `a message row never carries a derived status`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        val messages = mutableListOf<MessageEntity>()

        repository.sendMessage(message())

        // DELIVERED and READ describe the *other* device and are derived at render time.
        // Persisting either would make a stale row outrank the live marks.
        coVerify { messageDao.insertMessage(capture(messages)) }
        assertTrue(
            messages.none {
                it.status == MessageSendStatus.DELIVERED.name || it.status == MessageSendStatus.READ.name
            }
        )
    }

    // ---- the hard gate: no partial copy may reset the marks ---------------

    @Test
    fun `a remote conversation copy without marks does not erase the local ones`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow(
            lastReadAtJson = """{"$UID_A":900}""",
            lastDeliveredAtJson = """{"$UID_A":800}"""
        )
        // A document written before the marks existed — exactly the shape the deleted sync
        // loop used to rebuild a Conversation from, resetting both maps on every Chat open.
        every { firestoreMessageDataSource.observeConversation(CONVERSATION) } returns flowOf(
            mapOf(
                "id" to CONVERSATION,
                "participants" to listOf(UID_A, UID_B),
                "title" to "Anna",
                "createdAt" to CREATED_AT
            )
        )
        val stored = slot<ConversationEntity>()

        // toList, not first: the two branches of the observer run concurrently, and only a
        // completed collection guarantees the mirroring branch has finished its write.
        repository.observeConversation(CONVERSATION).toList()

        coVerify { messageDao.insertConversation(capture(stored)) }
        assertTrue(stored.captured.lastReadAtJson.contains("900"))
        assertTrue(stored.captured.lastDeliveredAtJson.contains("800"))
        assertFalse(stored.captured.archived)
    }

    @Test
    fun `a stale remote mark does not pull the local one backwards`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow(
            lastReadAtJson = """{"$UID_A":900}""",
            lastDeliveredAtJson = """{"$UID_A":800}"""
        )
        // The key is present but behind — a mark this device wrote that has not round-tripped
        // yet. An implementation reading "remote wins whenever the key is there" passes the
        // absent-map test above and regresses the mark on every echo; this is what catches it.
        every { firestoreMessageDataSource.observeConversation(CONVERSATION) } returns flowOf(
            mapOf(
                "id" to CONVERSATION,
                "participants" to listOf(UID_A, UID_B),
                "createdAt" to CREATED_AT,
                "lastReadAt" to mapOf(UID_A to 100L),
                "lastDeliveredAt" to mapOf(UID_A to 100L)
            )
        )
        val stored = slot<ConversationEntity>()

        repository.observeConversation(CONVERSATION).toList()

        coVerify { messageDao.insertConversation(capture(stored)) }
        assertTrue(stored.captured.lastReadAtJson.contains("\"$UID_A\":900"))
        assertTrue(stored.captured.lastDeliveredAtJson.contains("\"$UID_A\":800"))
    }

    @Test
    fun `a remote title never overwrites this device's own`() = runTest {
        coEvery { messageDao.getConversationById(CONVERSATION) } returns conversationRow()
        every { firestoreMessageDataSource.observeConversation(CONVERSATION) } returns flowOf(
            mapOf(
                "id" to CONVERSATION,
                "participants" to listOf(UID_A, UID_B),
                // A document written before the title stopped being shared, carrying the
                // *other* device's name for its partner — which is this user's own name.
                "title" to "Me, seen from the other phone",
                "createdAt" to CREATED_AT
            )
        )
        val stored = slot<ConversationEntity>()

        repository.observeConversation(CONVERSATION).toList()

        coVerify { messageDao.insertConversation(capture(stored)) }
        assertEquals("Anna", stored.captured.title)
    }

    private fun messageRow(at: Long) = MessageEntity(
        id = "message-$at",
        conversationId = CONVERSATION,
        senderId = UID_B,
        senderName = "Bob",
        content = "hello",
        sentAtMillis = at,
        messageType = "TEXT",
        attachmentsJson = "[]",
        isRead = false,
        replyToMessageId = null,
        syncedToFirestore = true,
        status = "SENT"
    )

    // ---- the unread count, which is where CQ-6 moved this guarantee to ------------------
    //
    // `ChatViewModel` used to fold the whole thread to produce this number, and its tests
    // asserted the behaviour there. It reads `observeUnreadCount` now — a `COUNT(*)` — so the
    // substance belongs here: which mark the count is taken against.

    @Test
    fun `the unread count is taken against this reader's own mark`() = runTest {
        every { messageDao.observeConversationById(CONVERSATION) } returns
            flowOf(conversationRow(lastReadAtJson = """{"$UID_A":$SENT_AT_MILLIS}"""))
        every { messageDao.observeUnreadCount(any(), any(), any()) } returns flowOf(0)

        repository.observeUnreadCount(CONVERSATION, UID_A).first()

        verify { messageDao.observeUnreadCount(CONVERSATION, UID_A, SENT_AT_MILLIS) }
    }

    @Test
    fun `a thread this reader has never opened counts every message`() = runTest {
        // `Long.MIN_VALUE` rather than 0 or "now": any real timestamp is greater, so every
        // message counts — which is the right answer for a thread nobody has read yet. Zero
        // would be almost right and wrong for any message predating the epoch; "now" would be
        // wrong for all of them.
        every { messageDao.observeConversationById(CONVERSATION) } returns flowOf(conversationRow())
        every { messageDao.observeUnreadCount(any(), any(), any()) } returns flowOf(0)

        repository.observeUnreadCount(CONVERSATION, UID_A).first()

        verify { messageDao.observeUnreadCount(CONVERSATION, UID_A, Long.MIN_VALUE) }
    }

    @Test
    fun `the other parent's mark is not mistaken for mine`() = runTest {
        // The marks are a map keyed by uid. Reading the wrong entry would show a parent a badge
        // cleared by somebody else having read the thread.
        every { messageDao.observeConversationById(CONVERSATION) } returns
            flowOf(conversationRow(lastReadAtJson = """{"$UID_B":$SENT_AT_MILLIS}"""))
        every { messageDao.observeUnreadCount(any(), any(), any()) } returns flowOf(0)

        repository.observeUnreadCount(CONVERSATION, UID_A).first()

        verify { messageDao.observeUnreadCount(CONVERSATION, UID_A, Long.MIN_VALUE) }
    }

    private fun conversationRow(
        lastReadAtJson: String = "{}",
        lastDeliveredAtJson: String = "{}"
    ) = ConversationEntity(
        id = CONVERSATION,
        participantsJson = """["$UID_A","$UID_B"]""",
        title = "Anna",
        lastReadAtJson = lastReadAtJson,
        lastDeliveredAtJson = lastDeliveredAtJson,
        createdAt = TIMESTAMP
    )

    private fun message() = Message(
        id = MESSAGE_ID,
        conversationId = CONVERSATION,
        senderId = UID_A,
        senderName = "Anna",
        content = "See you at 5",
        sentAtMillis = SENT_AT_MILLIS,
        messageType = MessageType.TEXT,
        status = MessageSendStatus.SENDING
    )

    /** A row the outbox should pick up: written locally, never acknowledged by the server. */
    private fun unsentRow(senderId: String = UID_A) = MessageEntity(
        id = MESSAGE_ID,
        conversationId = CONVERSATION,
        senderId = senderId,
        senderName = "Anna",
        content = "See you at 5",
        sentAtMillis = SENT_AT_MILLIS,
        messageType = MessageType.TEXT.name,
        syncedToFirestore = false,
        status = MessageSendStatus.ERROR.name
    )

    private companion object {
        const val UID_A = "uidA"
        const val UID_B = "uidB"
        const val CONVERSATION = "uidA__uidB"
        const val MESSAGE_ID = "message-1"
        const val AT_MILLIS = 1_754_000_000_000L
        const val CREATED_AT = "2026-08-01T10:00:00"

        /** The conversation's own creation time, which is still a local date-time. */
        val TIMESTAMP: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)

        /** 2026-08-01T10:00:00Z — the message under test's send instant. */
        const val SENT_AT_MILLIS = 1_785_578_400_000L

        /**
         * Two send instants (2026-06-21T00:00Z and ten minutes later) well in the past, so a
         * clock-derived mark is distinguishable from a message-derived one.
         */
        const val OLDER = 1_782_000_000_000L
        const val NEWEST = 1_782_000_600_000L
    }
}
