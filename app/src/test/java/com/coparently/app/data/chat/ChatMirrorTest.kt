package com.coparently.app.data.chat

import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The supervision loop (CQ-8).
 *
 * The class exists because a mirror that ends stays ended, and the app then looks entirely healthy
 * while receiving nothing. So these tests are about *restarting*: every one of them fails if the
 * loop gives up, which is the behaviour that shipped and was seen in production.
 *
 * **The loop is unbounded, so every test bounds it from the outside** — [runMirror]'s injected
 * `sleep` cancels after a few rounds. That is the shape the roadmap's caution asks for: a test of
 * a give-up path that spins the virtual clock instead of finishing is worse than no test, and with
 * a no-op `sleep` and a real `advanceUntilIdle` this file would hang rather than fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMirrorTest {

    private val myUid = "uid-alice"
    private val partnerUid = "uid-bob"

    private fun paired() = PairingState.Paired(
        partner = PartnerSummary(
            id = partnerUid,
            name = "Bob",
            email = "bob@example.com",
            pairedSinceMillis = null
        )
    )

    /** A flow that never emits and never ends — a listener that is simply healthy. */
    private fun <T> idleFlow(): Flow<T> = flow { awaitCancellation() }

    private fun mirrorWith(
        pairing: PairingState,
        messages: () -> Flow<List<Message>> = { idleFlow() },
        ensure: suspend () -> String = { "conv-id" }
    ): ChatMirror {
        val userRepository = mockk<UserRepository>(relaxed = true) {
            every { observeCurrentUserId() } returns MutableStateFlow(myUid)
        }
        val pairingRepository = mockk<PairingRepository>(relaxed = true) {
            every { observePairingState() } returns MutableStateFlow(pairing)
        }
        val messageRepository = mockk<MessageRepository>(relaxed = true) {
            coEvery { ensureConversation(any(), any(), any()) } coAnswers { ensure() }
            every { observeMessages(any()) } answers { messages() }
            every { observeConversation(any()) } answers { idleFlow<Conversation?>() }
        }
        return ChatMirror(userRepository, pairingRepository, messageRepository)
    }

    /**
     * Runs the loop until it has waited [rounds] times, then stops it.
     *
     * @return every interval it waited, in order.
     */
    private fun TestScope.runMirror(mirror: ChatMirror, rounds: Int = 3): List<Long> {
        val sleeps = mutableListOf<Long>()
        val job = launch {
            mirror.mirror { waited ->
                sleeps += waited
                if (sleeps.size >= rounds) throw CancellationException("enough for the test")
            }
        }
        advanceUntilIdle()
        job.cancel()
        return sleeps
    }

    @Test
    fun `a mirror that ends is started again`() = runTest {
        // The defect exactly. `MessageRepositoryImpl` ends both mirrors with a `.catch` — it must,
        // since an uncaught failure in a `viewModelScope.launch` terminates the process — and
        // `catch` *completes* the flow. A completed mirror used to mean no more messages, ever.
        var attaches = 0
        val mirror = mirrorWith(paired(), messages = {
            flow {
                attaches++
                emit(emptyList())
                // Returning is what a caught failure looks like from out here.
            }
        })

        val sleeps = runMirror(mirror)

        assertEquals(3, attaches, "expected one attach per round")
        assertEquals(3, sleeps.size)
    }

    @Test
    fun `a mirror that throws is restarted rather than killing the loop`() = runTest {
        // The other shape of the same event: something escapes past the repository's own `catch`.
        // It must not reach this coroutine — an uncaught failure here would take the process.
        var attaches = 0
        val mirror = mirrorWith(paired(), messages = {
            flow {
                attaches++
                error("listener denied")
            }
        })

        runMirror(mirror)

        assertEquals(3, attaches, "expected a restart after each throw")
    }

    @Test
    fun `it waits the full interval between attempts`() = runTest {
        val mirror = mirrorWith(paired(), messages = {
            flow {
                emit(emptyList())
            }
        })

        val sleeps = runMirror(mirror)

        // Pinned so shortening it is a deliberate act with a failing test: this loop is unbounded,
        // and its rate is the only thing keeping a permanently denied read from becoming a
        // permanently retried one.
        assertTrue(
            sleeps.all { it == ChatMirror.RESTART_DELAY_MILLIS },
            "unexpected waits: $sleeps"
        )
    }

    @Test
    fun `a healthy mirror is never restarted`() = runTest {
        // The flow parks instead of ending, which is what a working listener does. Nothing should
        // wait, because nothing should have to be re-attached.
        val sleeps = runMirror(mirrorWith(paired()))

        assertTrue(sleeps.isEmpty(), "a healthy mirror should never wait to re-attach: $sleeps")
    }

    @Test
    fun `an unpaired account attaches nothing`() = runTest {
        var attaches = 0
        val mirror = mirrorWith(PairingState.NotPaired(), messages = {
            flow {
                attaches++
                emit(emptyList())
            }
        })

        runMirror(mirror)

        assertEquals(0, attaches, "nothing to mirror without a co-parent")
    }

    @Test
    fun `a conversation that cannot be created attaches nothing this pass`() = runTest {
        // Without an id there is nothing to attach to — and attaching anyway is what produced the
        // incident, because the rules key a message read on the conversation's `participants`.
        var attaches = 0
        val mirror = mirrorWith(
            paired(),
            messages = {
                flow {
                    attaches++
                    emit(emptyList())
                }
            },
            ensure = { error("write failed") }
        )

        runMirror(mirror)

        assertEquals(0, attaches)
    }
}
