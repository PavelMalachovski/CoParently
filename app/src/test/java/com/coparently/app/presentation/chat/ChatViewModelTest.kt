package com.coparently.app.presentation.chat

import app.cash.turbine.test
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
import io.mockk.slot
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
    private lateinit var conversationsInRoom: MutableStateFlow<List<Conversation>>
    private lateinit var messageRepository: MessageRepository
    private lateinit var userRepository: UserRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        pairingState = MutableStateFlow(PairingState.Loading)
        signedInUid = MutableStateFlow<String?>(UID)
        conversationsInRoom = MutableStateFlow(emptyList())

        messageRepository = mockk(relaxed = true) {
            every { getConversations(any()) } returns conversationsInRoom
            every { getMessages(any()) } returns flowOf(emptyList())
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
        val created = slot<Conversation>()
        coVerify { messageRepository.createConversation(capture(created)) }
        assertEquals(setOf(UID, PARTNER), created.captured.participants.toSet())
    }

    @Test
    fun `an already known pairing opens the existing thread instead of a second one`() = runTest {
        pairingState.value = PairingState.Paired(partner())
        conversationsInRoom.value = listOf(existingThread())
        val viewModel = createViewModel()
        var opened: String? = null

        viewModel.startConversationWithPartner { opened = it }
        runCurrent()

        assertEquals(CONVERSATION, opened)
        coVerify(exactly = 0) { messageRepository.createConversation(any()) }
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
        coVerify(exactly = 0) { messageRepository.createConversation(any()) }
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
        every { messageRepository.getMessages(CONVERSATION) } returns flowOf(listOf(message()))
        val viewModel = createViewModel()

        viewModel.messages.test {
            assertEquals(emptyList<Message>(), awaitItem())

            viewModel.setConversationId(CONVERSATION)

            assertEquals(listOf(message()), awaitItem())
        }
    }

    @Test
    fun `switching conversation re-subscribes rather than keeping the first thread`() = runTest {
        every { messageRepository.getMessages(CONVERSATION) } returns flowOf(listOf(message()))
        every { messageRepository.getMessages(OTHER_CONVERSATION) } returns flowOf(emptyList())
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
    fun `conversations are requested for the signed-in user, not for an empty id`() = runTest {
        // `MessageRepositoryImpl.getConversations` currently ignores this argument and
        // returns every local conversation, which is what masked the same construction
        // bug here. Pinning the argument keeps that masking from becoming load-bearing.
        val viewModel = createViewModel()

        viewModel.conversations.test {
            awaitItem()
            advanceUntilIdle()
        }

        verify { messageRepository.getConversations(UID) }
        verify(exactly = 0) { messageRepository.getConversations("") }
    }

    @Test
    fun `signing out empties the conversation list`() = runTest {
        conversationsInRoom.value = listOf(existingThread())
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
        const val CONVERSATION = "conversation-1"
        const val OTHER_CONVERSATION = "conversation-2"
        val TIMESTAMP: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)
    }
}
