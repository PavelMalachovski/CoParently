package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.coparently.app.domain.chat.ConversationKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for [ConversationMigrator], the one-time-per-launch fold of a pair's legacy,
 * randomly-id'd conversations into the canonical (`ConversationKey`) one.
 *
 * Everything here runs over mocked [MessageDao] and [FirestoreMessageDataSource] instances, so
 * these tests prove the *orchestration*: which conversations are picked as candidates, in what
 * order local and remote writes happen, that the canonical conversation is never a candidate,
 * that an unrelated pair is left alone, and that a second run against the resulting (now
 * archived) state makes no further calls at all. They do not — and cannot — prove that the
 * `messages` document a real re-point targets is actually writable; that permission boundary is
 * covered separately, against the Firestore emulator, in
 * `firestore-tests/rules/conversations-messages.test.js`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationMigratorTest {

    private lateinit var messageDao: MessageDao
    private lateinit var firestoreMessageDataSource: FirestoreMessageDataSource
    private lateinit var migrator: ConversationMigrator

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0

        messageDao = mockk(relaxed = true)
        firestoreMessageDataSource = mockk(relaxed = true)
        migrator = ConversationMigrator(messageDao, firestoreMessageDataSource)
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `a legacy conversation with the same pair has its messages re-pointed and is archived`() =
        runTest {
            coEvery { messageDao.getActiveConversations() } returns flowOf(listOf(legacyRow()))
            coEvery { messageDao.getMessagesOnce(LEGACY_ID) } returns
                listOf(messageRow("msg-1"), messageRow("msg-2"))
            coEvery { messageDao.getConversationById(LEGACY_ID) } returns legacyRow()

            migrator.mergeLegacyConversations(UID_A, UID_B)

            coVerify(exactly = 1) { firestoreMessageDataSource.repointMessage("msg-1", CANONICAL_ID) }
            coVerify(exactly = 1) { firestoreMessageDataSource.repointMessage("msg-2", CANONICAL_ID) }
            coVerify(exactly = 1) { messageDao.repointMessages(LEGACY_ID, CANONICAL_ID) }
            coVerify(exactly = 1) {
                messageDao.insertConversation(match { it.id == LEGACY_ID && it.archived })
            }
            coVerify(exactly = 1) {
                firestoreMessageDataSource.setConversation(LEGACY_ID, mapOf("archived" to true))
            }
        }

    @Test
    fun `the canonical conversation is never treated as a legacy candidate`() = runTest {
        // Its own participants are, by definition, this same pair.
        coEvery { messageDao.getActiveConversations() } returns flowOf(listOf(canonicalRow()))

        migrator.mergeLegacyConversations(UID_A, UID_B)

        coVerify(exactly = 0) { messageDao.getMessagesOnce(CANONICAL_ID) }
        coVerify(exactly = 0) { messageDao.repointMessages(any(), any()) }
        coVerify(exactly = 0) { messageDao.insertConversation(any()) }
        coVerify(exactly = 0) { firestoreMessageDataSource.setConversation(any(), any()) }
    }

    @Test
    fun `running the merge twice makes no further writes the second time`() = runTest {
        // First call sees the legacy thread active; second call reflects the real DAO state
        // after archiving — `getActiveConversations` filters `archived = 0`, so it would no
        // longer be returned at all.
        every { messageDao.getActiveConversations() } returnsMany listOf(
            flowOf(listOf(legacyRow())),
            flowOf(emptyList())
        )
        coEvery { messageDao.getMessagesOnce(LEGACY_ID) } returns listOf(messageRow("msg-1"))
        coEvery { messageDao.getConversationById(LEGACY_ID) } returns legacyRow()

        migrator.mergeLegacyConversations(UID_A, UID_B)
        migrator.mergeLegacyConversations(UID_A, UID_B)

        // Exactly the first run's worth of writes — the second run added none.
        coVerify(exactly = 1) { firestoreMessageDataSource.repointMessage("msg-1", CANONICAL_ID) }
        coVerify(exactly = 1) { messageDao.repointMessages(LEGACY_ID, CANONICAL_ID) }
        coVerify(exactly = 1) { messageDao.insertConversation(any()) }
        coVerify(exactly = 1) { firestoreMessageDataSource.setConversation(any(), any()) }
    }

    @Test
    fun `a conversation belonging to a different pair is left untouched`() = runTest {
        val otherPair = ConversationEntity(
            id = "random-uuid-other-pair",
            participantsJson = """["$UID_A","carol-uid"]""",
            title = "Someone else",
            archived = false,
            createdAt = CREATED_AT
        )
        coEvery { messageDao.getActiveConversations() } returns flowOf(listOf(otherPair))

        migrator.mergeLegacyConversations(UID_A, UID_B)

        coVerify(exactly = 0) { messageDao.getMessagesOnce("random-uuid-other-pair") }
        coVerify(exactly = 0) { messageDao.repointMessages(any(), any()) }
        coVerify(exactly = 0) { messageDao.insertConversation(any()) }
    }

    @Test
    fun `messages keep their own ids and are never inserted, only re-pointed`() = runTest {
        coEvery { messageDao.getActiveConversations() } returns flowOf(listOf(legacyRow()))
        coEvery { messageDao.getMessagesOnce(LEGACY_ID) } returns listOf(messageRow("msg-1"))
        coEvery { messageDao.getConversationById(LEGACY_ID) } returns legacyRow()

        migrator.mergeLegacyConversations(UID_A, UID_B)

        // A message already present in the canonical thread cannot be duplicated: the only
        // local write this migration ever makes to a message row is the DAO's bulk `UPDATE`
        // (repointMessages), never an insert of an individual message.
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `a message that fails to re-point remotely leaves the whole legacy thread untouched`() =
        runTest {
            coEvery { messageDao.getActiveConversations() } returns flowOf(listOf(legacyRow()))
            coEvery { messageDao.getMessagesOnce(LEGACY_ID) } returns
                listOf(messageRow("msg-1"), messageRow("msg-2"))
            coEvery { firestoreMessageDataSource.repointMessage("msg-1", CANONICAL_ID) } returns Unit
            coEvery { firestoreMessageDataSource.repointMessage("msg-2", CANONICAL_ID) } throws
                RuntimeException("denied")

            migrator.mergeLegacyConversations(UID_A, UID_B)

            // Not "some messages moved, some didn't, and it's archived anyway" — the local
            // re-point and the archive must both wait for every remote re-point to succeed, so
            // a retried launch can still tell this thread needs another pass.
            coVerify(exactly = 0) { messageDao.repointMessages(any(), any()) }
            coVerify(exactly = 0) { messageDao.insertConversation(any()) }
            coVerify(exactly = 0) { firestoreMessageDataSource.setConversation(any(), any()) }
        }

    @Test
    fun `an unexpected local failure on one candidate does not block another candidate`() =
        runTest {
            val secondLegacyId = "random-uuid-legacy-2"
            val secondLegacy = ConversationEntity(
                id = secondLegacyId,
                participantsJson = """["$UID_A","$UID_B"]""",
                title = "Another old thread",
                archived = false,
                createdAt = CREATED_AT
            )
            coEvery { messageDao.getActiveConversations() } returns
                flowOf(listOf(legacyRow(), secondLegacy))
            // The first candidate's local read blows up unexpectedly (a Room I/O error, say).
            coEvery { messageDao.getMessagesOnce(LEGACY_ID) } throws IllegalStateException("disk full")
            coEvery { messageDao.getMessagesOnce(secondLegacyId) } returns listOf(messageRow("msg-9"))
            coEvery { messageDao.getConversationById(secondLegacyId) } returns secondLegacy

            // Must not throw: PairingRepositoryImpl relies on this to never escape.
            migrator.mergeLegacyConversations(UID_A, UID_B)

            // The second candidate still gets fully merged despite the first one's failure.
            coVerify(exactly = 1) { firestoreMessageDataSource.repointMessage("msg-9", CANONICAL_ID) }
            coVerify(exactly = 1) { messageDao.repointMessages(secondLegacyId, CANONICAL_ID) }
            coVerify(exactly = 1) {
                messageDao.insertConversation(match { it.id == secondLegacyId && it.archived })
            }
        }

    // ---- fixtures -----------------------------------------------------------

    private fun legacyRow() = ConversationEntity(
        id = LEGACY_ID,
        participantsJson = """["$UID_A","$UID_B"]""",
        title = "Old thread",
        archived = false,
        createdAt = CREATED_AT
    )

    private fun canonicalRow() = ConversationEntity(
        id = CANONICAL_ID,
        participantsJson = """["$UID_A","$UID_B"]""",
        title = "Co-parent chat",
        archived = false,
        createdAt = CREATED_AT
    )

    private fun messageRow(id: String) = MessageEntity(
        id = id,
        conversationId = LEGACY_ID,
        senderId = UID_A,
        senderName = "Anna",
        content = "hi",
        timestamp = CREATED_AT,
        messageType = "TEXT",
        status = "SENT"
    )

    private companion object {
        const val UID_A = "uidA"
        const val UID_B = "uidB"
        const val LEGACY_ID = "random-uuid-legacy"
        val CANONICAL_ID = ConversationKey.of(UID_A, UID_B)
        val CREATED_AT: LocalDateTime = LocalDateTime.of(2026, 7, 1, 9, 0)
    }
}
