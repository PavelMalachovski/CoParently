package com.coparently.app.presentation.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = emptyList()
            )

    /**
     * Messages of the selected conversation, re-subscribed whenever the selection changes.
     *
     * Same reason as [conversations], with a harder failure: the id is null at construction
     * time by definition, so the `combine` version subscribed to `observeMessages("")` and a
     * thread's messages could never appear at all.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) flowOf(emptyList()) else messageRepository.observeMessages(conversationId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

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

    fun setConversationId(conversationId: String) {
        _currentConversationId.value = conversationId
        launchGuarded("mark conversation as read") {
            if (currentUserId.value.isNotEmpty()) {
                messageRepository.markRead(conversationId, currentUserId.value)
            }
        }
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
    }
}
