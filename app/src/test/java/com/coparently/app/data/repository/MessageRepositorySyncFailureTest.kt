package com.coparently.app.data.repository

import app.cash.turbine.test
import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Guards the containment of a failing Firestore read in the chat observers.
 *
 * The live `messages` query is `conversationId == … ORDER BY timestamp ASC`, which Firestore
 * can only serve from a composite index. That index was missing from `firestore.indexes.json`,
 * so the snapshot listener reported `FAILED_PRECONDITION: The query requires an index`, the
 * failure escaped the repository into `ChatViewModel`'s `viewModelScope.launch`, and the
 * process was killed every time the user opened Chat.
 *
 * The nested sync loop those failures used to escape from is gone; the two observers that
 * replaced it inherit the same obligation, so these tests moved onto them rather than being
 * deleted with it. The index is configuration and cannot be asserted here. What is asserted
 * is the code-level defect: a failed remote read must degrade to "nothing new this round",
 * logged, with Room untouched and still driving the flow — never to a propagated exception.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositorySyncFailureTest {

    private lateinit var messageDao: MessageDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreMessageDataSource: FirestoreMessageDataSource
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        messageDao = mockk(relaxed = true)
        firebaseAuthService = mockk()
        firestoreMessageDataSource = mockk()
        repository = MessageRepositoryImpl(messageDao, firebaseAuthService, firestoreMessageDataSource)

        val firebaseUser = mockk<FirebaseUser> { every { uid } returns UID }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { messageDao.getConversationById(any()) } returns null
        every { messageDao.getMessages(CONVERSATION_ID) } returns flowOf(localMessages())
        every { messageDao.observeConversationById(CONVERSATION_ID) } returns flowOf(localConversation())
        every { firestoreMessageDataSource.observeConversation(CONVERSATION_ID) } returns flowOf(
            mapOf(
                "id" to CONVERSATION_ID,
                "participants" to listOf(UID, "uidB"),
                "title" to "Co-parent",
                "createdAt" to "2026-08-01T10:00:00"
            )
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `a missing index on the messages query does not propagate out of the observer`() = runTest {
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
            failing(missingIndex())

        // Fails (the exception escapes and the test errors out) without the `catch` on the
        // remote branch of MessageRepositoryImpl.observeMessages.
        repository.observeMessages(CONVERSATION_ID).toList()
    }

    @Test
    fun `a failing messages query still serves the local copy and writes nothing`() = runTest {
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
            failing(missingIndex())

        val messages = repository.observeMessages(CONVERSATION_ID).toList().last()

        // Room is the source of truth: the remote branch failing degrades this one step,
        // it does not blank the thread.
        assertEquals(1, messages.size)
        assertEquals(LOCAL_MESSAGE_ID, messages.first().id)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `a failed messages query is logged with the conversation and the query shape`() = runTest {
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
            failing(missingIndex())
        val logged = slot<String>()

        repository.observeMessages(CONVERSATION_ID).toList()

        verify { android.util.Log.w(any<String>(), capture(logged), any<Throwable>()) }
        // Swallowing silently is what makes an index outage invisible: the message has to
        // name the conversation, the query and where the index is declared.
        assertTrue(logged.captured.contains(CONVERSATION_ID))
        assertTrue(logged.captured.contains("timestamp"))
        assertTrue(logged.captured.contains("firestore.indexes.json"))
    }

    @Test
    fun `a non-Firestore failure in the messages flow is contained the same way`() = runTest {
        // The containment is about the collector, not about one exception type: any failure
        // of the remote read has to leave Room intact rather than kill the process.
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
            failing(IllegalStateException("listener died"))

        repository.observeMessages(CONVERSATION_ID).toList()

        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `messages are still mirrored into Room when the query succeeds`() = runTest {
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns flowOf(
            listOf(
                mapOf(
                    "id" to "msg-1",
                    "conversationId" to CONVERSATION_ID,
                    "senderId" to UID,
                    "senderName" to "Mom",
                    "content" to "See you at 5",
                    // A legacy string timestamp: documents in this format still exist and must
                    // still mirror. See `ChatMappersWireFormatTest` for both formats in detail.
                    "timestamp" to "2026-08-01T10:05:00",
                    "messageType" to "TEXT",
                    "attachments" to emptyList<String>(),
                    "isRead" to false,
                    "replyToMessageId" to ""
                )
            )
        )
        val entity = slot<MessageEntity>()

        repository.observeMessages(CONVERSATION_ID).toList()

        coVerify(exactly = 1) { messageDao.insertMessage(capture(entity)) }
        assertEquals("msg-1", entity.captured.id)
    }

    @Test
    fun `a failing conversation observer does not propagate and still serves the local row`() =
        runTest {
            every { firestoreMessageDataSource.observeConversation(CONVERSATION_ID) } returns
                failing(missingIndex())
            every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
                flowOf(emptyList())

            val conversation = repository.observeConversation(CONVERSATION_ID).toList().last()

            assertEquals(CONVERSATION_ID, conversation?.id)
            coVerify(exactly = 0) { messageDao.insertConversation(any()) }
        }

    // ---- the two observers are independent, not nested -------------------

    @Test
    fun `the conversation observer never subscribes to the messages listener`() = runTest {
        // The defect this whole task exists to undo was a message flow collected *inside*
        // the conversation collector. Every other stub here is a finite flow, so a nested
        // implementation would still terminate and every other assertion would still hold —
        // a never-completing messages source is what makes the nesting fatal, and the
        // verify below catches it even if it somehow were not.
        every { firestoreMessageDataSource.getMessages(any()) } returns MutableSharedFlow()

        repository.observeConversation(CONVERSATION_ID).test {
            assertEquals(CONVERSATION_ID, awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 0) { firestoreMessageDataSource.getMessages(any()) }
    }

    @Test
    fun `the messages observer never subscribes to the conversation listener`() = runTest {
        every { firestoreMessageDataSource.observeConversation(any()) } returns MutableSharedFlow()
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns flowOf(emptyList())

        repository.observeMessages(CONVERSATION_ID).test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 0) { firestoreMessageDataSource.observeConversation(any()) }
    }

    // ---- reconnecting after a failure (CQ-8) ------------------------------

    @Test
    fun `a listener that fails once is re-established, and mirrors what it then receives`() =
        runTest {
            // The production case, exactly: both listeners were denied about half a second
            // before `ensureConversation` wrote the conversation document. One retry catches it.
            // Before the retry existed, `catch` completed the mirror and the whole session ran
            // on Room alone while looking entirely healthy.
            var subscriptions = 0
            every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns flow {
                subscriptions++
                if (subscriptions == 1) throw missingIndex()
                emit(listOf(remoteMessage()))
            }
            val entity = slot<MessageEntity>()

            repository.observeMessages(CONVERSATION_ID).toList()

            assertEquals("the failed listener must be re-subscribed, not dropped", 2, subscriptions)
            coVerify(exactly = 1) { messageDao.insertMessage(capture(entity)) }
            assertEquals("msg-1", entity.captured.id)
        }

    @Test
    fun `the conversation listener reconnects too, not only the messages one`() = runTest {
        // Both branches ended in the same `catch`, so both had the same defect. Fixing one
        // would leave a session where messages arrive and read state never does.
        var subscriptions = 0
        every { firestoreMessageDataSource.observeConversation(CONVERSATION_ID) } returns flow {
            subscriptions++
            if (subscriptions == 1) throw missingIndex()
            emit(
                mapOf(
                    "id" to CONVERSATION_ID,
                    "participants" to listOf(UID, "uidB"),
                    "title" to "Co-parent",
                    "createdAt" to "2026-08-01T10:00:00"
                )
            )
        }
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns flowOf(emptyList())

        repository.observeConversation(CONVERSATION_ID).toList()

        assertEquals(2, subscriptions)
    }

    @Test
    fun `reconnection is bounded, so a broken deployment does not retry for ever`() = runTest {
        // The give-up path is deliberate: retrying for ever turns a genuinely broken rule into a
        // listener that reconnects for the life of the process. What must not happen is the
        // failure escaping - that is what used to kill the process on opening Chat.
        var subscriptions = 0
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns flow {
            subscriptions++
            throw missingIndex()
        }

        val messages = repository.observeMessages(CONVERSATION_ID).toList().last()

        assertTrue("it must retry at all", subscriptions > 1)
        assertTrue("but not for ever: $subscriptions attempts", subscriptions <= 16)
        assertEquals("Room still drives the thread", LOCAL_MESSAGE_ID, messages.first().id)
    }

    private fun remoteMessage() = mapOf(
        "id" to "msg-1",
        "conversationId" to CONVERSATION_ID,
        "senderId" to UID,
        "senderName" to "Mom",
        "content" to "See you at 5",
        "timestamp" to "2026-08-01T10:05:00",
        "messageType" to "TEXT",
        "attachments" to emptyList<String>(),
        "isRead" to false,
        "replyToMessageId" to ""
    )

    private fun missingIndex() = FirebaseFirestoreException(
        "The query requires an index.",
        FirebaseFirestoreException.Code.FAILED_PRECONDITION
    )

    private fun <T> failing(cause: Throwable): Flow<T> = flow { throw cause }

    private fun localConversation() = ConversationEntity(
        id = CONVERSATION_ID,
        participantsJson = """["$UID","uidB"]""",
        title = "Co-parent",
        createdAt = TIMESTAMP
    )

    private fun localMessages() = listOf(
        MessageEntity(
            id = LOCAL_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            senderId = UID,
            senderName = "Mom",
            content = "Already in Room",
            sentAtMillis = SENT_AT_MILLIS,
            messageType = "TEXT",
            attachmentsJson = "[]",
            isRead = false,
            replyToMessageId = null,
            syncedToFirestore = true,
            status = "SENT"
        )
    )

    private companion object {
        const val UID = "uidA"
        const val CONVERSATION_ID = "conv-1"
        const val LOCAL_MESSAGE_ID = "local-1"

        /** The conversation's creation time, which is still a local date-time. */
        val TIMESTAMP: LocalDateTime = LocalDateTime.of(2026, 8, 1, 10, 0)

        /** 2026-08-01T10:00:00Z, as a message send instant. */
        const val SENT_AT_MILLIS = 1_785_578_400_000L
    }
}
