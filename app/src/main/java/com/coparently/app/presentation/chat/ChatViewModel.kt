package com.coparently.app.presentation.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.chat.ChatReadState
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageType
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.Loadable
import com.coparently.app.presentation.common.valueOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * Whether this account has a co-parent to chat with, as far as the chat screen knows
 * *right now*.
 *
 * The three cases are genuinely different and the screen must treat them differently:
 * [Resolving] is "ask again in a moment", [NotPaired] is "there is nobody to chat with,
 * go and pair", and only [Linked] is actionable. Collapsing the first two into one
 * nullable partner id is what made the co-parent button look broken — a cold start that
 * had not yet learned about the pairing was indistinguishable from an unpaired account,
 * and both silently did nothing.
 */
sealed interface CoParentLink {

    /** The pairing state has not resolved yet (cold start, or a listener recovering). */
    data object Resolving : CoParentLink

    /** This account has no co-parent linked. */
    data object NotPaired : CoParentLink

    /** Linked to the co-parent with this Firebase UID. */
    data class Linked(val partnerId: String) : CoParentLink
}

/**
 * One-shot outcomes of a chat action that the screen has to tell the user about.
 *
 * A button press must always produce a visible reaction; these are the reactions that
 * are not "a conversation opened".
 */
sealed interface ChatEvent {

    /** The account has no co-parent — offer the pairing screen. */
    data object NoCoParent : ChatEvent

    /** The pairing state did not resolve in time; the action is worth retrying. */
    data object CoParentLinkPending : ChatEvent
}

/**
 * State and actions for the conversation list and a single chat thread.
 *
 * Everything session-dependent here is a *stream*, never a value captured in `init`.
 * The identity ([currentUserId]) follows Firebase Auth and the co-parent link
 * ([coParentLink]) follows [PairingRepository.observePairingState], so a pairing that is
 * established — or ended — while this screen is open is reflected without recreating the
 * ViewModel. The previous version read both once from a Room row that a freshly paired
 * device does not have yet, which left the "chat with my co-parent" action permanently
 * dead for that ViewModel instance.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val pairingRepository: PairingRepository,
    private val preferences: EncryptedPreferences
) : ViewModel() {

    private val _currentConversationId = MutableStateFlow<String?>(null)

    /**
     * The conversation [onThreadOpened] most recently opened, or `null` while nothing is
     * actually on screen. This is deliberately a *separate* signal from
     * [_currentConversationId]: the latter is also set by [openConversationWith] merely to
     * resolve or create the thread for navigation — the "start a conversation" FAB routinely
     * resolves an *existing* thread that already holds real unread messages from the
     * co-parent, and resolving its id must not by itself tell the co-parent "I read this."
     * Only [onThreadOpened] — called when the ChatScreen composable is actually displayed —
     * may advance this, and [onThreadClosed] clears it so the read/delivered collector below
     * stops re-asserting marks for a thread no longer on screen.
     */
    private val _openedConversationId = MutableStateFlow<String?>(null)

    /**
     * Unsent composer text per conversation, authoritative for the life of this ViewModel.
     *
     * The encrypted store behind it is what survives the ViewModel; this map is what makes a
     * read after a keystroke correct before the debounced write has landed.
     */
    private val drafts = mutableMapOf<String, String>()

    /** The conversation whose draft is typed but not yet written, or null when none is. */
    private var pendingDraft: String? = null

    /** The pending debounced write, cancelled and rescheduled on each keystroke. */
    private var draftWriteJob: Job? = null

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 1)

    /** One-shot outcomes the screen renders as a snackbar. See [ChatEvent]. */
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _isOpeningConversation = MutableStateFlow(false)

    /**
     * True while [startConversationWithPartner] is waiting on the pairing state or
     * creating the thread, so the entry point can show progress instead of looking inert.
     */
    val isOpeningConversation: StateFlow<Boolean> = _isOpeningConversation.asStateFlow()

    /**
     * The signed-in user's Firebase UID, or the empty string while signed out.
     *
     * Eagerly started: it is one auth-state listener, everything below depends on it, and
     * the actions read it without necessarily having a UI subscriber.
     */
    val currentUserId: StateFlow<String> = userRepository.observeCurrentUserId()
        .map { it.orEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ""
        )

    /** Whether there is a co-parent to chat with. See [CoParentLink]. */
    val coParentLink: StateFlow<CoParentLink> = pairingRepository.observePairingState()
        .map { it.toCoParentLink() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = CoParentLink.Resolving
        )

    /**
     * Runs [block] in [viewModelScope] with a failure boundary around it.
     *
     * Every chat action below reaches Firestore. An uncaught failure in a
     * `viewModelScope.launch` is not delivered to any handler — it reaches the thread's
     * default uncaught-exception handler and terminates the process. That is exactly how a
     * missing composite index on `messages` (`conversationId ==` + `orderBy timestamp`) turned
     * a degraded remote read into a crash on opening Chat.
     *
     * Room is the offline-first source of truth here, so a remote failure is recoverable:
     * the local data stays on screen and the next sync retries. The failure is logged with
     * [operation] so it is recognisable in logcat rather than silently swallowed.
     * [kotlinx.coroutines.CancellationException] is rethrown — cancellation is not a failure.
     *
     * @param operation Short description of the work, used as the log context.
     * @param block The work to run.
     */
    private fun launchGuarded(operation: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                Log.w(TAG, "Chat operation failed: $operation", e)
            }
        }
    }

    /**
     * Logs a failed observer and lets the flow continue with a fallback value.
     *
     * The `stateIn` flows below are not launched through [launchGuarded] and have no failure
     * boundary of their own: an exception reaching `stateIn` cancels [viewModelScope], which
     * on an unhandled path terminates the process. The repository already contains remote
     * failures, so what this catches is a local one — but the screen surviving with stale
     * data beats the app dying.
     *
     * @param operation Short description, used as the log context.
     * @param cause The failure.
     * @param fallback What to emit in place of the failed value.
     */
    private suspend fun failSoft(operation: String, cause: Throwable, fallback: suspend () -> Unit) {
        if (cause is CancellationException) throw cause
        Log.w(TAG, "Chat flow failed: $operation", cause)
        fallback()
    }

    /**
     * The co-parent thread, as a single-element list while it exists.
     *
     * There is exactly one conversation per account pair and its id is a pure function of
     * the two uids, so this is derived from the session and the pairing rather than fetched
     * as a list. The account-wide list accessor it replaces was the thing the deleted sync
     * loop traversed.
     *
     * `flatMapLatest`, not `combine`: the inner flow has to be *built from* the current id,
     * and a `combine` builds it once, at construction, from whatever the id happens to be
     * then — the empty string.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<Loadable<List<Conversation>>> =
        combine(currentUserId, coParentLink) { userId, link -> userId to link }
            .flatMapLatest { (userId, link) ->
                when (link) {
                    // Not an answer yet. Collapsing this into an empty list is the same false
                    // assertion `Loadable` exists to remove, one layer further down: the screen
                    // would say "no conversations" for the frames before the pairing listener
                    // has reported anything at all.
                    CoParentLink.Resolving -> flowOf(Loadable.Loading)

                    CoParentLink.NotPaired -> flowOf(Loadable.Loaded(emptyList()))

                    is CoParentLink.Linked -> {
                        // A null id means the session has not resolved, so there is no key to
                        // build a thread from — but the pairing *has* answered, and signing out
                        // is a real answer too. Known to be empty, not unknown.
                        val conversationId = conversationIdOrNull(userId, link.partnerId)
                        if (conversationId == null) {
                            flowOf(Loadable.Loaded(emptyList()))
                        } else {
                            messageRepository.observeConversation(conversationId)
                                .map { Loadable.Loaded(listOfNotNull(it)) }
                        }
                    }
                }
            }
            // A failed read is a different question from an unfinished one: this one has been
            // asked and answered badly, so the screen gets an answer rather than a skeleton it
            // would sit under for ever.
            .catch { e -> failSoft("observe conversation", e) { emit(Loadable.Loaded(emptyList())) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = Loadable.Loading
            )

    /**
     * The same thread, flattened, for everything inside this class that derives from it.
     *
     * The derivations below — the message list's delivery ticks, the unread count — are all
     * "given this thread, compute that", and neither has anything different to say while the
     * thread is still loading: an unresolved conversation and an absent one both mean there is
     * nothing to derive. Only the screen needs the distinction, which is why [conversations]
     * carries it and this does not.
     */
    private val loadedConversations: StateFlow<List<Conversation>> = conversations
        .map { it.valueOrNull.orEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

    /**
     * The selected conversation's raw messages, tagged with the id they belong to.
     *
     * Backs [messages] only — the read/delivered collector in `init` subscribes to
     * [openedThreadMessages] instead, which is keyed by a separate signal that only
     * [onThreadOpened] sets, precisely so that setting *this* id (also done by
     * [openConversationWith], merely to resolve/create a thread for navigation) can never by
     * itself cause a mark to be written. Same reason [conversations] gives for using
     * `flatMapLatest` over `combine` applies here — the id is null at construction time by
     * definition, so a `combine` version would subscribe to `observeMessages("")` once and
     * a thread's messages could never appear at all.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val currentThreadMessages: Flow<Pair<String?, List<Message>>> = _currentConversationId
        .flatMapLatest { conversationId ->
            val raw = if (conversationId == null) {
                flowOf(emptyList())
            } else {
                messageRepository.observeMessages(conversationId)
            }
            raw.map { conversationId to it }
        }

    /**
     * Messages of the selected conversation, each carrying the status the UI should render.
     *
     * A message this account sent is promoted from [MessageSendStatus.SENT] to
     * [MessageSendStatus.DELIVERED] or [MessageSendStatus.READ] via [ChatReadState.statusFor],
     * using the *open* conversation's own marks — matched by [Conversation.id] against
     * [conversations] rather than blindly taking its single element, in case the two ever
     * disagree (e.g. a conversation id passed in before [conversations] has resolved).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = combine(
        currentThreadMessages,
        loadedConversations,
        currentUserId
    ) { (conversationId, rawMessages), convs, myUid ->
        val conversation = convs.firstOrNull { it.id == conversationId }
        val otherUid = conversation?.participants?.firstOrNull { it != myUid }
        if (conversation == null || myUid.isEmpty() || otherUid == null) {
            rawMessages
        } else {
            rawMessages.map { message -> message.withDerivedStatus(myUid, otherUid, conversation) }
        }
    }
        .catch { e -> failSoft("observe messages", e) { emit(emptyList()) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

    /**
     * Number of the co-parent's messages this account has not yet read.
     *
     * **A `COUNT(*)` out of Room, and nothing else (CQ-6/CQ-8).** This used to answer the number
     * by subscribing to `observeMessages` and folding the whole thread through
     * `ChatReadState.unreadCount` — and `NavGraph.rememberChatUnreadCount()` collects this from an
     * Activity-scoped instance for the entire process lifetime. So a badge was materialising every
     * message a pair had ever exchanged, forever, to produce one integer that
     * `MessageDao.observeUnreadCount` produces with a `COUNT(*)`; and, worse, it was the only
     * thing holding the Firestore mirrors open, which is why they could never be restarted after
     * a failure. `ChatMirror` owns them now, from the process rather than from a screen.
     *
     * The conversation id is **derived** (`ConversationKey.of`) rather than taken from
     * [conversations], so this does not subscribe to the conversation document either — that
     * subscription is the other half of what the badge was holding open. It is the same id the
     * thread itself uses, by construction: the key is a pure function of the two uids.
     *
     * Kept as an independent subscription from [messages] — mirroring `HomeViewModel.unreadCount`'s
     * Home-tile figure — so a failure in one cannot blank the other.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val unreadCount: StateFlow<Int> = combine(currentUserId, coParentLink) { uid, link -> uid to link }
        .map { (uid, link) ->
            val partnerId = (link as? CoParentLink.Linked)?.partnerId
            if (partnerId == null) null else conversationIdOrNull(uid, partnerId)?.let { it to uid }
        }
        .distinctUntilChanged()
        .flatMapLatest { thread ->
            if (thread == null) flowOf(0) else messageRepository.observeUnreadCount(thread.first, thread.second)
        }
        .catch { e -> failSoft("observe unread count", e) { emit(0) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = 0
        )

    /**
     * The *opened* thread's raw messages — same shape as [currentThreadMessages], but keyed
     * by [_openedConversationId] rather than [_currentConversationId]. This is what the
     * read/delivered collector below subscribes to, so resolving a conversation id for
     * navigation (`openConversationWith`) can never be mistaken for the thread actually being
     * on screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val openedThreadMessages: Flow<Pair<String?, List<Message>>> = _openedConversationId
        .flatMapLatest { conversationId ->
            val raw = if (conversationId == null) {
                flowOf(emptyList())
            } else {
                messageRepository.observeMessages(conversationId)
            }
            raw.map { conversationId to it }
        }

    init {
        // Re-asserts the read/delivered marks every time the *opened* thread's messages
        // change — not only when the thread is first opened. A one-shot mark on open alone
        // misses two cases: Room not having ingested any messages yet (fresh install, right
        // after pairing, right after a database wipe — the repository's newest-message lookup
        // is null then and writes nothing), and a message arriving from the co-parent while
        // the thread stays open, which must clear the badge and advance the tick without the
        // user leaving and re-entering the screen.
        //
        // `retry` rather than `catch`-and-stop: unlike `messages`/`conversations`/
        // `unreadCount`, this collector has no `stateIn` behind it to fall back on — if a
        // transient failure (e.g. a Room hiccup) were allowed to end the flow, the auto
        // re-assert would silently stay dead for the rest of this ViewModel's life with no
        // way to notice. Retrying re-subscribes from `_openedConversationId`'s current value,
        // so it heals once the underlying cause clears.
        launchGuarded("re-assert read/delivered marks") {
            openedThreadMessages
                .retry { cause ->
                    if (cause is CancellationException) throw cause
                    Log.w(TAG, "Re-assert read/delivered marks failed; retrying", cause)
                    delay(MARK_RETRY_DELAY_MS)
                    true
                }
                .collect { (conversationId, _) ->
                    if (conversationId == null) return@collect
                    val userId = currentUserId.value
                    if (userId.isEmpty()) return@collect
                    messageRepository.markRead(conversationId, userId)
                    messageRepository.markDelivered(conversationId, userId)
                }
        }
    }

    /**
     * Upcoming non-private events (next [UPCOMING_DAYS] days) offered when the user
     * starts a change request from the chat. Private events are excluded — a change
     * request is a conversation with the co-parent about a shared event.
     */
    val upcomingEvents: StateFlow<List<Event>> = eventRepository
        .getEventsByDateRange(
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(UPCOMING_DAYS)
        )
        .map { events ->
            events.filter { !it.isPrivate }.sortedBy { it.startDateTime }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

    /**
     * Called when the chat thread screen is displayed for [conversationId].
     *
     * Selects which conversation's messages this ViewModel observes, and — separately —
     * marks the thread as actually open via [_openedConversationId]. The `init` collector is
     * where `markRead`/`markDelivered` are actually written, keyed off that second signal:
     * deriving them from the messages flow itself, rather than firing a single call right
     * here, is what lets the mark land once Room has actually ingested the thread's messages
     * instead of being lost on a fresh install or right after pairing.
     */
    fun onThreadOpened(conversationId: String) {
        _currentConversationId.value = conversationId
        _openedConversationId.value = conversationId
        // Anything this device wrote and never got onto the server goes out now. Before the
        // outbox existed, a send that failed once stayed failed forever and the co-parent
        // simply never received it.
        launchGuarded("flush chat outbox") { messageRepository.flushOutbox() }
    }

    /**
     * The unsent text the composer should open with for [conversationId].
     *
     * Read through the in-memory map first so a draft typed a moment ago is returned even if
     * the debounced write has not landed yet.
     */
    fun draftFor(conversationId: String): String =
        drafts[conversationId] ?: preferences.getChatDraft(conversationId)

    /**
     * Records what the user has typed but not sent.
     *
     * Kept in memory immediately and written to the encrypted store on a debounce — a write per
     * keystroke would encrypt a string on the main thread for every character. The pending write
     * is flushed synchronously by [onThreadClosed], which is what actually covers the reported
     * case: switching tabs clears the Chat back-stack entry, and with it this ViewModel, well
     * inside the debounce window.
     */
    fun onDraftChanged(conversationId: String, text: String) {
        drafts[conversationId] = text
        pendingDraft = conversationId
        // A hand-rolled debounce rather than `Flow.debounce`, which is still `@FlowPreview` in
        // coroutines 1.9 and would need a module-wide opt-in for one call site.
        draftWriteJob?.cancel()
        draftWriteJob = viewModelScope.launch {
            delay(DRAFT_PERSIST_DEBOUNCE_MS)
            flushDraft()
        }
    }

    /** Drops the draft for [conversationId] — the composer is empty, so there is nothing unsent. */
    private fun clearDraft(conversationId: String) {
        drafts.remove(conversationId)
        if (pendingDraft == conversationId) pendingDraft = null
        preferences.putChatDraft(conversationId, "")
    }

    /** Writes whatever the composer last held, now rather than on the debounce. */
    private fun flushDraft() {
        draftWriteJob?.cancel()
        val conversationId = pendingDraft ?: return
        pendingDraft = null
        preferences.putChatDraft(conversationId, drafts[conversationId].orEmpty())
    }

    /**
     * Called when the chat thread screen leaves composition.
     *
     * Clears [_openedConversationId] so the read/delivered collector stops re-asserting
     * marks for a thread that is no longer on screen — most of the time this ViewModel is
     * cleared along with the screen anyway (it is scoped to the nav back stack entry), but a
     * configuration change disposes and recomposes the screen while the same ViewModel
     * instance survives, and this keeps that window from being treated as "still open."
     */
    fun onThreadClosed() {
        _openedConversationId.value = null
        // Synchronous on purpose: the tab switch that disposes this screen also clears the
        // back-stack entry and this ViewModel, so a coroutine scheduled here would be cancelled
        // before it ran and the draft would be lost — the exact reported bug.
        flushDraft()
    }

    override fun onCleared() {
        flushDraft()
        super.onCleared()
    }

    fun sendMessage(content: String, type: MessageType = MessageType.TEXT, attachments: List<String> = emptyList()) {
        val conversationId = _currentConversationId.value
        if (conversationId == null) {
            Log.w(TAG, "Send ignored: no conversation is open")
            return
        }
        val userId = currentUserId.value
        if (userId.isEmpty()) {
            Log.w(TAG, "Send ignored: the session has not resolved a uid yet")
            return
        }

        // Cleared here, not on the send's success. The screen empties the composer the instant
        // this returns, so anything still in `drafts` is text the user can no longer see — and
        // two paths never reach the success line: a refused write, which `sendMessage` rethrows
        // after marking the row ERROR, and a tab switch, which clears this back-stack-scoped
        // ViewModel mid-flight while `onThreadClosed` has already flushed the draft to storage.
        // Either way the next open would re-seed the composer with a message that is already in
        // the thread and queued for retry, inviting the parent to send it twice. The SENDING row
        // is the record of what was sent; `flushOutbox` is what retries it.
        clearDraft(conversationId)

        launchGuarded("send message") {
            val user = userRepository.getCurrentUser()
            val senderName = user?.name ?: "Unknown"

            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = userId,
                senderName = senderName,
                content = content,
                sentAtMillis = System.currentTimeMillis(),
                messageType = type,
                attachments = attachments,
                status = MessageSendStatus.SENDING
            )
            messageRepository.sendMessage(message)
        }
    }

    /**
     * Opens a conversation with the paired co-parent — reusing the existing 1:1
     * conversation if there is one, otherwise creating it — then invokes [onOpened]
     * with its id so the screen can navigate to it.
     *
     * Waits for the session and the pairing state to resolve rather than reading whatever
     * they happen to hold at the moment of the tap: on a cold start, or right after
     * pairing, they are still [CoParentLink.Resolving] and the old early `return` made the
     * button permanently inert. Every path that does not open a conversation emits a
     * [ChatEvent] instead, because a button press with no visible reaction reads as a bug.
     *
     * @param onOpened Invoked with the conversation id once it exists.
     */
    fun startConversationWithPartner(onOpened: (String) -> Unit) {
        if (_isOpeningConversation.value) return
        _isOpeningConversation.value = true

        launchGuarded("open conversation with partner") {
            try {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    val userId = currentUserId.first { it.isNotEmpty() }
                    userId to coParentLink.first { it != CoParentLink.Resolving }
                }
                val link = resolved?.second
                when {
                    resolved == null -> _events.emit(ChatEvent.CoParentLinkPending)
                    link !is CoParentLink.Linked -> _events.emit(ChatEvent.NoCoParent)
                    else -> onOpened(openConversationWith(resolved.first, link.partnerId))
                }
            } finally {
                _isOpeningConversation.value = false
            }
        }
    }

    /**
     * The id of the 1:1 conversation between [userId] and [partnerId], creating it if it
     * does not exist yet.
     *
     * No lookup: the id is derived from the pair, and the create is idempotent, so this is
     * a single call whichever device runs it and however many times.
     */
    private suspend fun openConversationWith(userId: String, partnerId: String): String {
        val partnerName = userRepository.getUserById(partnerId)?.name ?: "Co-parent"
        val conversationId = messageRepository.ensureConversation(userId, partnerId, partnerName)
        _currentConversationId.value = conversationId
        return conversationId
    }

    /**
     * Handles the thread's pull-to-refresh gesture.
     *
     * The thread itself is a realtime listener now, so there is nothing left to fetch —
     * what a manual refresh can still usefully do is re-assert the read mark, which a
     * previous refused or offline write may have left behind on the server.
     */
    fun refreshThread() {
        launchGuarded("refresh thread") {
            messageRepository.flushOutbox()
            val conversationId = _currentConversationId.value ?: return@launchGuarded
            val userId = currentUserId.value
            if (userId.isNotEmpty()) {
                messageRepository.markRead(conversationId, userId)
            }
        }
    }

    /**
     * Re-sends one message the user tapped in the thread.
     *
     * The whole outbox rather than that one row: if an earlier message is still stuck, sending
     * this one first would deliver the conversation out of order, and the flush is cheap when
     * there is nothing else queued.
     */
    fun resendFailedMessages() {
        launchGuarded("resend failed messages") { messageRepository.flushOutbox() }
    }

    /**
     * The deterministic conversation id for [userId] and [partnerId], or `null` when the
     * pair cannot form one — an unresolved session (blank uid) or, defensively, a partner
     * id equal to this user's.
     */
    private fun conversationIdOrNull(userId: String, partnerId: String): String? =
        runCatching { ConversationKey.of(userId, partnerId) }.getOrNull()

    /**
     * This message, promoted to [MessageSendStatus.DELIVERED] or [MessageSendStatus.READ] via
     * [ChatReadState.statusFor] if it is one of [myUid]'s own — a message from the co-parent
     * is rendered exactly as stored, since only the sender's own bubble carries a tick.
     */
    private fun Message.withDerivedStatus(myUid: String, otherUid: String, conversation: Conversation): Message =
        if (senderId != myUid) {
            this
        } else {
            copy(
                status = ChatReadState.statusFor(
                    message = this,
                    otherUid = otherUid,
                    lastReadAt = conversation.lastReadAt,
                    lastDeliveredAt = conversation.lastDeliveredAt
                )
            )
        }

    /**
     * The pairing state as the chat entry point needs to see it.
     *
     * A [PairingState.Paired] carrying a blank partner id is what the pairing repository
     * falls back to when the partner's profile document cannot be read. There is nothing
     * to start a conversation with in that case, so it reads as [CoParentLink.NotPaired].
     */
    private fun PairingState.toCoParentLink(): CoParentLink = when (this) {
        PairingState.Loading -> CoParentLink.Resolving
        is PairingState.NotPaired -> CoParentLink.NotPaired
        is PairingState.Paired ->
            partner.id.takeIf { it.isNotBlank() }?.let { CoParentLink.Linked(it) }
                ?: CoParentLink.NotPaired
    }

    private companion object {
        const val UPCOMING_DAYS = 30L
        const val TAG = "ChatViewModel"

        /**
         * Quiet period before an unsent draft is written to the encrypted store.
         *
         * Only a backstop for process death: a tab switch flushes synchronously through
         * [onThreadClosed], which is the case that actually loses text today.
         */
        const val DRAFT_PERSIST_DEBOUNCE_MS = 400L

        /** How long a `WhileSubscribed` flow stays warm across a configuration change. */
        const val SUBSCRIPTION_TIMEOUT_MS = 5000L

        /**
         * How long the co-parent action waits for auth and the pairing snapshot. Long
         * enough for a cold Firestore listener, short enough that the user gets an answer.
         */
        const val RESOLVE_TIMEOUT_MS = 5000L

        /** Backoff before retrying the read/delivered re-assert collector after a failure. */
        const val MARK_RETRY_DELAY_MS = 2000L
    }
}
