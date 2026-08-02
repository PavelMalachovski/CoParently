package com.coparently.app.data.repository

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
            timestamp = TIMESTAMP,
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
        val TIMESTAMP: LocalDateTime = LocalDateTime.of(2026, 8, 1, 10, 0)
    }
}
