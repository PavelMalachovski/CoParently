package com.coparently.app.presentation.chat

import app.cash.turbine.test
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageType
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.Loadable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for [ChatViewModel]'s session-dependent state.
 *
 * Every defect these pin came from the same mistake: reading a value once in `init` and
 * treating that snapshot as the truth for the whole life of the ViewModel. On a device
 * where Chat was opened before the pairing had reached the local Room row, the co-parent
 * button was dead forever — no screen, no error, nothing — and the messages flow was
 * subscribed to `getMessages("")`, an id no thread ever has, so a conversation's messages
 * could never appear at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var pairingState: MutableStateFlow<PairingState>
    private lateinit var signedInUid: MutableStateFlow<String?>
    private lateinit var conversationInRoom: MutableStateFlow<Conversation?>
    private lateinit var messageRepository: MessageRepository
    private lateinit var userRepository: UserRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        pairingState = MutableStateFlow(PairingState.Loading)
        signedInUid = MutableStateFlow<String?>(UID)
        conversationInRoom = MutableStateFlow(null)

        messageRepository = mockk(relaxed = true) {
            every { observeConversation(any()) } returns conversationInRoom
            every { observeMessages(any(), any()) } returns flowOf(emptyList())
            coEvery { ensureConversation(any(), any(), any()) } answers {
                ConversationKey.of(firstArg(), secondArg())
            }
        }
        userRepository = mockk(relaxed = true) {
            every { observeCurrentUserId() } returns signedInUid
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 1a: the co-parent link is reactive -----------------------------

    @Test
    fun `a pairing that arrives after construction makes the co-parent action work`() = runTest {
        // The reported symptom, reproduced: Chat is opened while the pairing state has not
        // resolved yet. The old ViewModel snapshotted an empty partner id here and the
        // button stayed inert for the rest of its life.
        val viewModel = createViewModel()
        var opened: String? = null

        // runCurrent, not advanceUntilIdle: advancing virtual time would blow the action's
        // own resolve timeout and turn this into the "link pending" case instead.
        viewModel.startConversationWithPartner { opened = it }
        runCurrent()
        assertNull("nothing should open while the link is still resolving", opened)

        pairingState.value = PairingState.Paired(partner())
        runCurrent()

        assertTrue("the action must complete once the pairing arrives", opened != null)
        assertEquals(CONVERSATION, opened)
        coVerify { messageRepository.ensureConversation(UID, PARTNER, any()) }
    }

    @Test
    fun `an already known pairing opens the existing thread instead of a second one`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        conversationInRoom.value = existingThread()
        val viewModel = createViewModel()
        var opened: String? = null

        viewModel.startConversationWithPartner { opened = it }
        runCurrent()

        // The id is derived from the participant pair, so "reuse" is not a lookup any more:
        // the same call on either device can only ever land on the one deterministic thread.
        assertEquals(CONVERSATION, opened)
    }

    @Test
    fun `an unpairing while chat is on screen is reflected without recreating the view model`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        val viewModel = createViewModel()

        viewModel.coParentLink.test {
            // The StateFlow's seed value, before the pairing flow is subscribed.
            assertEquals(CoParentLink.Resolving, awaitItem())
            assertEquals(CoParentLink.Linked(PARTNER), awaitItem())

            pairingState.value = PairingState.NotPaired()

            assertEquals(CoParentLink.NotPaired, awaitItem())
        }
    }

    // ---- 1b: a button press always produces a reaction ------------------

    @Test
    fun `an account with no co-parent is told so instead of nothing happening`() = runTest {
        pairingState.value = PairingState.NotPaired()
        val viewModel = createViewModel()

        viewModel.events.test {
            viewModel.startConversationWithPartner { }
            runCurrent()
            assertEquals(ChatEvent.NoCoParent, awaitItem())
        }
        coVerify(exactly = 0) { messageRepository.ensureConversation(any(), any(), any()) }
    }

    @Test
    fun `a link that never resolves reports itself as pending rather than failing silently`() = runTest {
        // Stays Loading: offline with a cold Firestore cache, or signed out.
        val viewModel = createViewModel()

        viewModel.events.test {
            viewModel.startConversationWithPartner { }
            advanceUntilIdle()
            assertEquals(ChatEvent.CoParentLinkPending, awaitItem())
        }
    }

    // ---- 1c: messages follow the selected conversation ------------------

    @Test
    fun `messages follow the selected conversation id`() = runTest {
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(message()))
        val viewModel = createViewModel()

        viewModel.messages.test {
            assertEquals(emptyList<Message>(), awaitItem())

            viewModel.onThreadOpened(CONVERSATION)

            assertEquals(listOf(message()), awaitItem())
        }
    }

    @Test
    fun `switching conversation re-subscribes rather than keeping the first thread`() = runTest {
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(message()))
        every { messageRepository.observeMessages(OTHER_CONVERSATION, any()) } returns flowOf(emptyList())
        val viewModel = createViewModel()

        viewModel.messages.test {
            awaitItem()
            viewModel.onThreadOpened(CONVERSATION)
            assertEquals(1, awaitItem().size)
            viewModel.onThreadOpened(OTHER_CONVERSATION)
            assertEquals(0, awaitItem().size)
        }
    }

    // ---- 1d: conversations follow the signed-in user --------------------

    @Test
    fun `the thread is observed under the deterministic id, never under an empty one`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        val viewModel = createViewModel()

        viewModel.conversations.test {
            // Only here to hold a subscriber open while the id is derived; the assertions are
            // the verifies below, so how many states pass by on the way is not this test's
            // business.
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        verify { messageRepository.observeConversation(CONVERSATION) }
        // An unresolved session cannot form a conversation key, so no thread is observed at
        // all rather than one under a bogus id built from the empty string.
        verify(exactly = 0) { messageRepository.observeConversation("") }
    }

    @Test
    fun `signing out empties the conversation list`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        conversationInRoom.value = existingThread()
        val viewModel = createViewModel()

        viewModel.conversations.test {
            // The seed is `Loading`, not an empty list. Those used to be the same value, which
            // is what made the Chat tab show "no conversations yet" for the frames before Room
            // answered (UX-2).
            assertEquals(Loadable.Loading, awaitItem())
            assertEquals(1, (awaitItem() as Loadable.Loaded).value.size)

            signedInUid.value = null

            // Signed out is a real answer — an empty list that is *known* to be empty.
            assertEquals(Loadable.Loaded(emptyList<Conversation>()), awaitItem())
        }
    }

    // ---- 1e: the read/delivered marks fire from the messages flow, not just on open -----
    //
    // Task 4 left two gaps: a fresh install (or right after pairing, or after a database
    // wipe) has nothing in Room yet when the thread opens, so a one-shot mark-on-open call
    // sees an empty table and writes nothing; and a message arriving from the co-parent
    // while the thread stays open must still clear the badge and advance the tick without
    // the user leaving and re-entering. Both are fixed by the same hook: re-asserting both
    // marks every time the open thread's messages flow emits.

    @Test
    fun `opening the thread calls markRead exactly once`() = runTest {
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(message()))
        val viewModel = createViewModel()

        viewModel.onThreadOpened(CONVERSATION)
        advanceUntilIdle()

        coVerify(exactly = 1) { messageRepository.markRead(CONVERSATION, UID) }
    }

    @Test
    fun `ingesting a message batch calls markDelivered`() = runTest {
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(message()))
        val viewModel = createViewModel()

        viewModel.onThreadOpened(CONVERSATION)
        advanceUntilIdle()

        coVerify(exactly = 1) { messageRepository.markDelivered(CONVERSATION, UID) }
    }

    @Test
    fun `a message batch arriving while the thread stays open re-asserts both marks again`() = runTest {
        val incoming = MutableStateFlow(listOf(message()))
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns incoming
        val viewModel = createViewModel()

        viewModel.onThreadOpened(CONVERSATION)
        advanceUntilIdle()
        incoming.value = listOf(message(), message().copy(id = "message-2"))
        advanceUntilIdle()

        coVerify(exactly = 2) { messageRepository.markRead(CONVERSATION, UID) }
        coVerify(exactly = 2) { messageRepository.markDelivered(CONVERSATION, UID) }
    }

    @Test
    fun `a thread that is never opened never has its marks written`() = runTest {
        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { messageRepository.markRead(any(), any()) }
        coVerify(exactly = 0) { messageRepository.markDelivered(any(), any()) }
    }

    @Test
    fun `resolving an existing thread from the FAB does not mark its unread messages read`() = runTest {
        // The FAB's job is to reuse an existing thread rather than create a second one, so the
        // conversation it resolves routinely already holds real unread messages from the
        // co-parent. Resolving the id — via startConversationWithPartner/openConversationWith —
        // is a different event from the ChatScreen actually opening the thread, and must not by
        // itself be enough to tell the co-parent "I read this."
        pairingState.value = PairingState.Paired(partner())
        conversationInRoom.value = existingThread()
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(message()))
        val viewModel = createViewModel()

        viewModel.startConversationWithPartner { }
        advanceUntilIdle()

        coVerify(exactly = 0) { messageRepository.markRead(any(), any()) }
        coVerify(exactly = 0) { messageRepository.markDelivered(any(), any()) }
    }

    // ---- 1f: my own messages render the tick the conversation's marks support -----------

    @Test
    fun `my message is rendered READ once the other parent's read mark passes it`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        conversationInRoom.value = existingThread().copy(lastReadAt = mapOf(PARTNER to AFTER_SENT_AT_MILLIS))
        val myMessage = message().copy(senderId = UID, status = MessageSendStatus.SENT)
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(myMessage))
        val viewModel = createViewModel()

        viewModel.messages.test {
            skipItems(1) // the StateFlow's seed value, before anything is subscribed
            viewModel.onThreadOpened(CONVERSATION)
            advanceUntilIdle()
            assertEquals(listOf(MessageSendStatus.READ), expectMostRecentItem().map { it.status })
        }
    }

    @Test
    fun `my message is rendered DELIVERED when only the delivery mark has passed it`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        conversationInRoom.value =
            existingThread().copy(lastDeliveredAt = mapOf(PARTNER to AFTER_SENT_AT_MILLIS))
        val myMessage = message().copy(senderId = UID, status = MessageSendStatus.SENT)
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(myMessage))
        val viewModel = createViewModel()

        viewModel.messages.test {
            skipItems(1)
            viewModel.onThreadOpened(CONVERSATION)
            advanceUntilIdle()
            assertEquals(listOf(MessageSendStatus.DELIVERED), expectMostRecentItem().map { it.status })
        }
    }

    @Test
    fun `my message stays SENT when neither mark has reached it yet`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        conversationInRoom.value = existingThread()
        val myMessage = message().copy(senderId = UID, status = MessageSendStatus.SENT)
        every { messageRepository.observeMessages(CONVERSATION, any()) } returns flowOf(listOf(myMessage))
        val viewModel = createViewModel()

        viewModel.messages.test {
            skipItems(1)
            viewModel.onThreadOpened(CONVERSATION)
            advanceUntilIdle()
            assertEquals(listOf(MessageSendStatus.SENT), expectMostRecentItem().map { it.status })
        }
    }

    // ---- 1g: the unread count is a Room COUNT(*), not a folded thread -------------------
    //
    // These two used to stub `observeMessages` and assert the number the ViewModel derived from
    // the whole thread. It does not derive it any more (CQ-6/CQ-8): the count comes from
    // `observeUnreadCount`, a `COUNT(*)`, and the *substance* — a co-parent's message counted
    // against my own read mark — is asserted where it now lives, in
    // `MessageRepositoryReadStateTest`. What is left for the ViewModel to get right is which
    // thread it asks about, and that is what these two check.

    @Test
    fun `unread count comes from the thread derived for this pair`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        every { messageRepository.observeUnreadCount(CONVERSATION, UID) } returns flowOf(3)
        val viewModel = createViewModel()

        viewModel.unreadCount.test {
            advanceUntilIdle()
            assertEquals(3, expectMostRecentItem())
        }
    }

    @Test
    fun `unread count asks nothing at all without a co-parent`() = runTest {
        // Not merely zero: an unpaired account has no thread, so there is nothing to subscribe
        // to. The badge holding a subscription open for the life of the process is the defect
        // this whole change is about.
        pairingState.value = PairingState.NotPaired()
        val viewModel = createViewModel()

        viewModel.unreadCount.test {
            advanceUntilIdle()
            assertEquals(0, expectMostRecentItem())
        }
        verify(exactly = 0) { messageRepository.observeUnreadCount(any(), any()) }
    }

    // ---- the draft belongs to the composer, not to the send's outcome ----

    @Test
    fun `a refused send still clears the draft, so the message is not offered twice`() = runTest {
        // The send fails the way a denied Firestore write fails: `sendMessage` marks the row
        // ERROR and rethrows. Under a draft cleared on the success path, the text stayed in the
        // store and the next open re-seeded the composer with a message already in the thread.
        coEvery { messageRepository.sendMessage(any()) } throws IllegalStateException("denied")
        val preferences = draftStore()
        val viewModel = createViewModel(preferences)
        advanceUntilIdle()

        viewModel.onThreadOpened(CONVERSATION)
        viewModel.onDraftChanged(CONVERSATION, DRAFT)
        viewModel.sendMessage(DRAFT)
        advanceUntilIdle()

        assertEquals("", viewModel.draftFor(CONVERSATION))
        verify { preferences.putChatDraft(CONVERSATION, "") }
    }

    @Test
    fun `leaving the tab straight after a send does not persist what was just sent`() = runTest {
        // No `advanceUntilIdle` between the send and the disposal: the send coroutine has not
        // run yet, which is exactly the window a tab switch lands in — it clears the Chat
        // back-stack entry and this ViewModel while `onThreadClosed` flushes synchronously.
        val preferences = draftStore()
        val viewModel = createViewModel(preferences)
        advanceUntilIdle()

        viewModel.onThreadOpened(CONVERSATION)
        viewModel.onDraftChanged(CONVERSATION, DRAFT)
        viewModel.sendMessage(DRAFT)
        viewModel.onThreadClosed()
        advanceUntilIdle()

        verify(exactly = 0) { preferences.putChatDraft(CONVERSATION, DRAFT) }
        assertEquals("", viewModel.draftFor(CONVERSATION))
    }

    private fun createViewModel(
        preferences: EncryptedPreferences = draftStore()
    ): ChatViewModel {
        val eventRepository = mockk<EventRepository> {
            every { getEventsByDateRange(any(), any()) } returns flowOf(emptyList())
        }
        val pairingRepository = mockk<PairingRepository> {
            every { observePairingState() } returns pairingState
        }
        coEvery { userRepository.getUserById(PARTNER) } returns null
        return ChatViewModel(
            messageRepository,
            userRepository,
            eventRepository,
            pairingRepository,
            preferences
        )
    }

    /** An empty draft store, which is what a device that has never typed one holds. */
    private fun draftStore() = mockk<EncryptedPreferences>(relaxed = true) {
        every { getChatDraft(any()) } returns ""
    }

    private fun partner() = PartnerSummary(
        id = PARTNER,
        name = "Bob Novak",
        email = "bob@example.com",
        pairedSinceMillis = null
    )

    private fun existingThread() = Conversation(
        id = CONVERSATION,
        participants = listOf(UID, PARTNER),
        title = "Bob Novak",
        createdAt = TIMESTAMP
    )

    private fun message() = Message(
        id = "message-1",
        conversationId = CONVERSATION,
        senderId = PARTNER,
        senderName = "Bob Novak",
        content = "Can we swap Friday?",
        sentAtMillis = SENT_AT_MILLIS,
        messageType = MessageType.TEXT
    )

    private companion object {
        const val UID = "user-a"
        const val PARTNER = "user-b"

        /** What `ConversationKey.of(UID, PARTNER)` derives; kept literal so the test pins it. */
        const val CONVERSATION = "user-a__user-b"
        const val OTHER_CONVERSATION = "conversation-2"

        /** Unsent text in the composer. */
        const val DRAFT = "are you free on Friday?"

        /** The conversation's creation time, which is still a local date-time. */
        val TIMESTAMP: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)

        /** 2026-08-01T10:00:00Z — the test message's send instant. */
        const val SENT_AT_MILLIS = 1_785_578_400_000L

        /** A mark a second past the message, so it covers it. */
        const val AFTER_SENT_AT_MILLIS = SENT_AT_MILLIS + 1_000L
    }
}
