package com.coparently.app.presentation.chat

import app.cash.turbine.test
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageType
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
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
            every { observeMessages(any()) } returns flowOf(emptyList())
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
        every { messageRepository.observeMessages(CONVERSATION) } returns flowOf(listOf(message()))
        val viewModel = createViewModel()

        viewModel.messages.test {
            assertEquals(emptyList<Message>(), awaitItem())

            viewModel.setConversationId(CONVERSATION)

            assertEquals(listOf(message()), awaitItem())
        }
    }

    @Test
    fun `switching conversation re-subscribes rather than keeping the first thread`() = runTest {
        every { messageRepository.observeMessages(CONVERSATION) } returns flowOf(listOf(message()))
        every { messageRepository.observeMessages(OTHER_CONVERSATION) } returns flowOf(emptyList())
        val viewModel = createViewModel()

        viewModel.messages.test {
            awaitItem()
            viewModel.setConversationId(CONVERSATION)
            assertEquals(1, awaitItem().size)
            viewModel.setConversationId(OTHER_CONVERSATION)
            assertEquals(0, awaitItem().size)
        }
    }

    // ---- 1d: conversations follow the signed-in user --------------------

    @Test
    fun `the thread is observed under the deterministic id, never under an empty one`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        val viewModel = createViewModel()

        viewModel.conversations.test {
            awaitItem()
            advanceUntilIdle()
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
            // The StateFlow's seed value, before the Room-backed flow is subscribed.
            assertEquals(emptyList<Conversation>(), awaitItem())
            assertEquals(1, awaitItem().size)

            signedInUid.value = null

            assertEquals(emptyList<Conversation>(), awaitItem())
        }
    }

    private fun createViewModel(): ChatViewModel {
        val eventRepository = mockk<EventRepository> {
            every { getEventsByDateRange(any(), any()) } returns flowOf(emptyList())
        }
        val pairingRepository = mockk<PairingRepository> {
            every { observePairingState() } returns pairingState
        }
        coEvery { userRepository.getUserById(PARTNER) } returns null
        return ChatViewModel(messageRepository, userRepository, eventRepository, pairingRepository)
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
        timestamp = TIMESTAMP,
        messageType = MessageType.TEXT
    )

    private companion object {
        const val UID = "user-a"
        const val PARTNER = "user-b"

        /** What `ConversationKey.of(UID, PARTNER)` derives; kept literal so the test pins it. */
        const val CONVERSATION = "user-a__user-b"
        const val OTHER_CONVERSATION = "conversation-2"
        val TIMESTAMP: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)
    }
}
