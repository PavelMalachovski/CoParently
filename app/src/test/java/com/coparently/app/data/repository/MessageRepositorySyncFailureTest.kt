package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the containment of a failing Firestore read in the chat sync path.
 *
 * The live `messages` query is `conversationId == … ORDER BY timestamp ASC`, which Firestore
 * can only serve from a composite index. That index was missing from `firestore.indexes.json`,
 * so the snapshot listener reported `FAILED_PRECONDITION: The query requires an index`, the
 * failure escaped [MessageRepositoryImpl.syncWithFirestore] into `ChatViewModel`'s
 * `viewModelScope.launch`, and the process was killed every time the user opened Chat.
 *
 * The index is configuration and cannot be asserted here. What these tests pin down is the
 * code-level defect: a failed remote read must degrade to "nothing synced this round", logged,
 * with Room untouched as the source of truth — never to a propagated exception.
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
        every { firestoreMessageDataSource.getConversations(UID) } returns flowOf(
            listOf(
                mapOf(
                    "id" to CONVERSATION_ID,
                    "participants" to listOf(UID, "uidB"),
                    "title" to "Co-parent",
                    "unreadCount" to 0L,
                    "createdAt" to "2026-08-01T10:00:00"
                )
            )
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `a missing index on the messages query does not propagate out of syncWithFirestore`() =
        runTest {
            every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
                failing(missingIndex())

            // Fails (the exception escapes and the test errors out) without the `catch`
            // on the messages flow in MessageRepositoryImpl.syncMessagesForConversation.
            repository.syncWithFirestore()
        }

    @Test
    fun `a missing index on the messages query leaves the conversation synced and no messages written`() =
        runTest {
            every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
                failing(missingIndex())

            repository.syncWithFirestore()

            // The conversation that was read before the messages query failed is still
            // mirrored into Room: the failure degrades this one step, it does not abort sync.
            coVerify(exactly = 1) { messageDao.insertConversation(any()) }
            coVerify(exactly = 0) { messageDao.insertMessage(any()) }
        }

    @Test
    fun `a failed messages query is logged with the conversation and the query shape`() = runTest {
        every { firestoreMessageDataSource.getMessages(CONVERSATION_ID) } returns
            failing(missingIndex())
        val logged = slot<String>()

        repository.syncWithFirestore()

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

        repository.syncWithFirestore()

        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `messages are still written to Room when the query succeeds`() = runTest {
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

        repository.syncWithFirestore()

        coVerify(exactly = 1) { messageDao.insertMessage(capture(entity)) }
        assertTrue(entity.captured.id == "msg-1")
    }

    private fun missingIndex() = FirebaseFirestoreException(
        "The query requires an index.",
        FirebaseFirestoreException.Code.FAILED_PRECONDITION
    )

    private fun failing(cause: Throwable): Flow<List<Map<String, Any>>> = flow { throw cause }

    private companion object {
        const val UID = "uidA"
        const val CONVERSATION_ID = "conv-1"
    }
}
