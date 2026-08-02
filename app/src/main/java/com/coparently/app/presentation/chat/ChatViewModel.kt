package com.coparently.app.presentation.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.chat.ChatReadState
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageTemplate
import com.coparently.app.domain.model.MessageType
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val pairingRepository: PairingRepository
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
    val conversations: StateFlow<List<Conversation>> =
        combine(currentUserId, coParentLink) { userId, link -> userId to link }
            .flatMapLatest { (userId, link) ->
                val conversationId = (link as? CoParentLink.Linked)
                    ?.let { conversationIdOrNull(userId, it.partnerId) }
                if (conversationId == null) {
                    flowOf(emptyList())
                } else {
                    messageRepository.observeConversation(conversationId).map { listOfNotNull(it) }
                }
            }
            .catch { e -> failSoft("observe conversation", e) { emit(emptyList()) } }
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
        conversations,
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
     * There is exactly one conversation per pair, so this is that thread's own
     * [ChatReadState.unreadCount] rather than anything summed across a list. Kept as an
     * independent subscription from [messages] — mirroring `HomeViewModel.unreadCount`'s
     * Home-tile figure — so a failure in one cannot blank the other.
     *
     * The inner `observeMessages` subscription is keyed on the conversation *id*
     * (`distinctUntilChanged`), not on [conversations] itself: [conversations] re-emits on
     * every `lastReadAt`/`lastDeliveredAt` write — which, with this task, now includes every
     * mark this ViewModel writes — and restarting the messages subscription on each of those
     * would be pure churn, since the id it is keyed on has not actually changed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val unreadCount: StateFlow<Int> = combine(
        conversations.map { it.firstOrNull()?.id }
            .distinctUntilChanged()
            .flatMapLatest { conversationId ->
                if (conversationId == null) flowOf(emptyList()) else messageRepository.observeMessages(conversationId)
            },
        conversations,
        currentUserId
    ) { rawMessages, convs, myUid ->
        ChatReadState.unreadCount(rawMessages, myUid, convs.firstOrNull()?.lastReadAt?.get(myUid))
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
    }

    fun sendMessage(content: String, type: MessageType = MessageType.TEXT, attachments: List<String> = emptyList()) {
        val conversationId = _currentConversationId.value ?: return
        val userId = currentUserId.value
        if (userId.isEmpty()) return

        launchGuarded("send message") {
            val user = userRepository.getCurrentUser()
            val senderName = user?.name ?: "Unknown"

            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = userId,
                senderName = senderName,
                content = content,
                timestamp = LocalDateTime.now(),
                messageType = type,
                attachments = attachments,
                status = MessageSendStatus.SENDING
            )
            messageRepository.sendMessage(message)
        }
    }

    fun sendTemplateMessage(template: MessageTemplate, filledContent: String) {
        sendMessage(filledContent, MessageType.TEXT)
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
            val conversationId = _currentConversationId.value ?: return@launchGuarded
            val userId = currentUserId.value
            if (userId.isNotEmpty()) {
                messageRepository.markRead(conversationId, userId)
            }
        }
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
