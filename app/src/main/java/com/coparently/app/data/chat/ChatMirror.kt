package com.coparently.app.data.chat

import android.util.Log
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the chat's Firestore listeners running for the life of the process, and restarts them
 * when they stop (CQ-8).
 *
 * **Why this class exists at all.** The two chat mirrors were being held open by a *screen*:
 * `NavGraph.rememberChatUnreadCount()` takes an Activity-scoped `ChatViewModel` and collects its
 * unread count forever, and that count used to subscribe to `observeMessages`. So the badge —
 * a piece of UI — was the only thing keeping the co-parent's messages arriving, which had two
 * consequences and both were bad. The count materialised the whole thread to answer a number that
 * `MessageDao.observeUnreadCount` answers with a `COUNT(*)`; and because the subscriber count
 * never fell to zero, `SharingStarted.WhileSubscribed` could never restart anything.
 *
 * That second half is the production defect. `MessageRepositoryImpl` ends both mirrors with a
 * `.catch` — it must, since an uncaught failure in a `viewModelScope.launch` terminates the
 * process — and `catch` *completes* the flow. After it fires, `merge(mirror, local)` runs on Room
 * alone: the app looks entirely healthy and receives nothing, until the process restarts. It was
 * seen exactly once and explains itself: on the first launch after install both listeners were
 * denied about half a second before `ensureConversation` had created the conversation document.
 *
 * **Two layers of recovery, deliberately different.** `MessageRepositoryImpl.reconnecting()` is a
 * fast, *bounded* retry — eight attempts, exponential, capped at a minute — for a blip. This is
 * the slow outer supervisor for an outage that outlives it. The roadmap's caution against an
 * unbounded retry is about the inner one, and it still holds: making *that* unbounded would
 * hammer a permanently broken rule and leave the give-up path spinning the virtual clock in any
 * test. The outer loop runs at [RESTART_DELAY_MILLIS], which on a genuinely broken rule is twelve
 * attach attempts an hour — the price of not silently losing a co-parent's messages for the rest
 * of the session, which in this product is the failure that matters most.
 *
 * **It awaits `ensureConversation` before subscribing**, which is the other structural fix the
 * roadmap named and the direct answer to what was observed.
 *
 * The listener cost is not new. The Activity-scoped collector already held both listeners for the
 * whole process; this moves them somewhere that says so and can restart them.
 */
@Singleton
class ChatMirror @Inject constructor(
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val messageRepository: MessageRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts mirroring, and keeps it started.
     *
     * Called from `CoPlanlyApplication.onCreate`, beside `SessionProfileSynchronizer.start()` and
     * `TelemetryConsentApplier.start()` — the mirror belongs to the process, which is the whole
     * point of moving it off a screen.
     */
    fun start() {
        scope.launch { mirror() }
    }

    /**
     * The supervision loop.
     *
     * @param sleep How to wait between restarts. A parameter so a test can drive it: the
     *   give-up-and-retry behaviour is the entire subject of this class, and it is untestable
     *   against a real five-minute delay.
     */
    internal suspend fun mirror(sleep: suspend (Long) -> Unit = { delay(it) }) {
        combine(
            userRepository.observeCurrentUserId(),
            pairingRepository.observePairingState()
        ) { uid, pairing -> threadFor(uid, pairing) }
            // Restart only when the *pair* changes. `observePairingState` re-emits on every
            // invite and profile write, and rebuilding two Firestore listeners for those would
            // be churn that looks exactly like the outage this class exists to end.
            .distinctUntilChanged()
            // `collectLatest`: signing out or unpairing cancels the mirror below rather than
            // leaving it attached to a thread this account no longer has.
            .collectLatest { thread ->
                if (thread == null) return@collectLatest
                mirrorThread(thread, sleep)
            }
    }

    /** The conversation to mirror, or null when there is nobody to chat with yet. */
    private fun threadFor(uid: String?, pairing: PairingState): ChatThread? {
        val myUid = uid?.takeIf { it.isNotBlank() } ?: return null
        val partnerUid = (pairing as? PairingState.Paired)?.partner?.id?.takeIf { it.isNotBlank() }
            ?: return null
        return ChatThread(myUid = myUid, partnerUid = partnerUid)
    }

    /**
     * Mirrors one thread until the caller cancels.
     *
     * The two listeners are supervised **independently**. Waiting for both before restarting
     * either would leave a dead messages listener parked behind a healthy conversation listener,
     * which is the same silent failure one level down.
     */
    private suspend fun mirrorThread(thread: ChatThread, sleep: suspend (Long) -> Unit) {
        val conversationId = ensureConversationId(thread) ?: return
        coroutineScope {
            launch {
                supervise("conversation", sleep) {
                    messageRepository.observeConversation(conversationId).collect { }
                }
            }
            launch {
                supervise("messages", sleep) {
                    messageRepository.observeMessages(conversationId).collect { }
                }
            }
        }
    }

    /**
     * Creates the conversation document if it is missing, and returns its id.
     *
     * **Awaited before either listener attaches.** Subscribing first is what produced the
     * incident: the rules key a message read on the conversation's `participants`, so a listener
     * that arrives before the document does is denied, and the denial completes the flow.
     */
    private suspend fun ensureConversationId(thread: ChatThread): String? =
        try {
            messageRepository.ensureConversation(thread.myUid, thread.partnerUid, title = "")
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Not fatal on its own — the document may already exist and only the write failed —
            // but without an id there is nothing to attach to, so this pass gives up and the
            // next pairing emission starts a new one.
            Log.w(TAG, "ensureConversation failed; not attaching this pass", e)
            null
        }

    /**
     * Runs [block] forever, restarting it after [RESTART_DELAY_MILLIS] whenever it returns.
     *
     * A mirror flow *returning* is not success — it means its `.catch` fired and the mirror is
     * over. Throwing past that `catch` is the other shape of the same event. Neither may escape
     * into this coroutine, because an uncaught failure here would take the process with it.
     *
     * **`CancellationException` is rethrown rather than treated as a failure.** `runCatching`
     * would swallow it, and this loop would then sleep and re-attach a listener for a thread the
     * account has just left — signing out or unpairing cancels through exactly this path.
     */
    private suspend fun supervise(
        what: String,
        sleep: suspend (Long) -> Unit,
        block: suspend () -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                block()
                Log.w(TAG, "Chat $what mirror ended; restarting")
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Chat $what mirror threw; restarting", e)
            }
            sleep(RESTART_DELAY_MILLIS)
        }
    }

    /** One pair's thread. */
    private data class ChatThread(val myUid: String, val partnerUid: String)

    companion object {
        private const val TAG = "ChatMirror"

        /**
         * How long to wait before re-attaching a mirror that ended.
         *
         * Five minutes, not five seconds. A listener that just gave up did so after
         * `reconnecting()` had already spent eight attempts over roughly two minutes, so
         * whatever is wrong is not momentary — and this loop has no bound, so its rate is the
         * only thing keeping a permanently denied read from becoming a permanently retried one.
         */
        const val RESTART_DELAY_MILLIS: Long = 5L * 60 * 1000
    }
}
