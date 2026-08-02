package com.coparently.app.presentation.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageTemplate
import com.coparently.app.domain.model.MessageType
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository
) : ViewModel() {

    private val _currentConversationId = MutableStateFlow<String?>(null)

    private val _currentUserId = MutableStateFlow<String>("")
    val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

    // Firebase UID of the paired co-parent, or null when the account is not paired.
    // The chat entry points use this to decide whether to open a conversation or
    // send the user to the pairing screen (an unpaired account has no one to chat with).
    private val _partnerId = MutableStateFlow<String?>(null)
    val partnerId: StateFlow<String?> = _partnerId.asStateFlow()

    init {
        launchGuarded("initial chat sync") {
            userRepository.getCurrentUser()?.let { user ->
                _currentUserId.value = user.id
                _partnerId.value = user.partnerId
                messageRepository.syncWithFirestore()
            }
        }
    }

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

    val conversations: StateFlow<List<Conversation>> = _currentUserId
        .combine(messageRepository.getConversations(_currentUserId.value)) { userId, conversations ->
            if (userId.isNotEmpty()) conversations else emptyList()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val messages: StateFlow<List<Message>> = _currentConversationId
        .combine(messageRepository.getMessages(_currentConversationId.value ?: "")) { conversationId, messages ->
            if (conversationId != null) messages else emptyList()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
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
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setConversationId(conversationId: String) {
        _currentConversationId.value = conversationId
        launchGuarded("mark conversation as read") {
            if (_currentUserId.value.isNotEmpty()) {
                messageRepository.markAsRead(conversationId, _currentUserId.value)
            }
        }
    }

    fun sendMessage(content: String, type: MessageType = MessageType.TEXT, attachments: List<String> = emptyList()) {
        val conversationId = _currentConversationId.value ?: return
        val userId = _currentUserId.value
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

    fun createConversation(otherUserId: String, title: String) {
        val userId = _currentUserId.value
        if (userId.isEmpty()) return

        launchGuarded("create conversation") {
            val conversation = Conversation(
                id = UUID.randomUUID().toString(),
                participants = listOf(userId, otherUserId),
                title = title,
                createdAt = LocalDateTime.now()
            )
            messageRepository.createConversation(conversation)
            _currentConversationId.value = conversation.id
        }
    }

    /**
     * Opens a conversation with the paired co-parent — reusing the existing 1:1
     * conversation if there is one, otherwise creating it — then invokes [onOpened]
     * with its id so the screen can navigate to it. No-op when the account is not
     * paired; callers must route to pairing in that case (see [partnerId]).
     */
    fun startConversationWithPartner(onOpened: (String) -> Unit) {
        val userId = _currentUserId.value
        val partner = _partnerId.value
        if (userId.isEmpty() || partner.isNullOrEmpty()) return

        launchGuarded("open conversation with partner") {
            val existing = conversations.value.firstOrNull {
                it.participants.toSet() == setOf(userId, partner)
            }
            val conversationId = existing?.id ?: run {
                val partnerName = userRepository.getUserById(partner)?.name ?: "Co-parent"
                val conversation = Conversation(
                    id = UUID.randomUUID().toString(),
                    participants = listOf(userId, partner),
                    title = partnerName,
                    createdAt = LocalDateTime.now()
                )
                messageRepository.createConversation(conversation)
                conversation.id
            }
            _currentConversationId.value = conversationId
            onOpened(conversationId)
        }
    }

    /**
     * Refresh messages for the current conversation.
     * Issue 6.2: Pull-to-refresh functionality.
     */
    fun refreshMessages() {
        launchGuarded("refresh messages") {
            val conversationId = _currentConversationId.value
            if (conversationId != null) {
                messageRepository.syncWithFirestore()
            }
        }
    }

    private companion object {
        const val UPCOMING_DAYS = 30L
        const val TAG = "ChatViewModel"
    }
}
