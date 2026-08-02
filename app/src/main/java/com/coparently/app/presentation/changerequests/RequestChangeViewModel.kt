package com.coparently.app.presentation.changerequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * State of the "request a change" form.
 */
sealed interface RequestChangeUiState {
    data object Loading : RequestChangeUiState

    /** Event loaded; the form can be shown. */
    data class Ready(val event: Event) : RequestChangeUiState

    /** Request is being written/synced; keeps the event so the form stays visible. */
    data class Sending(val event: Event) : RequestChangeUiState
    data object Sent : RequestChangeUiState
    data class Error(val message: String) : RequestChangeUiState
}

/**
 * ViewModel for proposing a new time for an existing event to the other parent.
 */
@HiltViewModel
class RequestChangeViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val changeRequestRepository: ChangeRequestRepository,
    private val userRepository: UserRepository,
    private val messageRepository: com.coparently.app.domain.repository.MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RequestChangeUiState>(RequestChangeUiState.Loading)
    val uiState: StateFlow<RequestChangeUiState> = _uiState.asStateFlow()

    fun loadEvent(eventId: String) {
        viewModelScope.launch {
            val event = eventRepository.getEventById(eventId)
            _uiState.value = if (event != null) {
                RequestChangeUiState.Ready(event)
            } else {
                RequestChangeUiState.Error("Event not found")
            }
        }
    }

    /**
     * Creates and sends the change request to the paired co-parent.
     *
     * When [conversationId] is provided (the request was started from a chat),
     * a structured message summarising the proposal is also posted to that
     * conversation so it appears in the thread.
     */
    fun submit(
        event: Event,
        proposedStart: LocalDateTime,
        proposedEnd: LocalDateTime?,
        note: String?,
        conversationId: String? = null
    ) {
        if (_uiState.value is RequestChangeUiState.Sending) return

        viewModelScope.launch {
            val user = userRepository.getCurrentUser()
            if (user == null) {
                _uiState.value = RequestChangeUiState.Error("Not signed in")
                return@launch
            }
            val partnerId = user.partnerId
            if (partnerId.isNullOrEmpty()) {
                _uiState.value =
                    RequestChangeUiState.Error("Pair with your co-parent first to send change requests")
                return@launch
            }

            _uiState.value = RequestChangeUiState.Sending(event)
            try {
                changeRequestRepository.createChangeRequest(
                    ChangeRequest(
                        id = UUID.randomUUID().toString(),
                        eventId = event.id,
                        eventTitle = event.title,
                        requestedBy = user.id,
                        requestedTo = partnerId,
                        currentStartDateTime = event.startDateTime,
                        currentEndDateTime = event.endDateTime,
                        proposedStartDateTime = proposedStart,
                        proposedEndDateTime = proposedEnd,
                        note = note?.takeIf { it.isNotBlank() },
                        createdAt = LocalDateTime.now()
                    )
                )
                if (conversationId != null) {
                    postChatMessage(conversationId, user.id, user.name, event, proposedStart, proposedEnd, note)
                }
                _uiState.value = RequestChangeUiState.Sent
            } catch (
                // Firestore/network failures surface as a form error, not a crash
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                _uiState.value =
                    RequestChangeUiState.Error(e.message ?: "Failed to send the change request")
            }
        }
    }

    @Suppress("LongParameterList") // one message built from the request's fields
    private suspend fun postChatMessage(
        conversationId: String,
        senderId: String,
        senderName: String,
        event: Event,
        proposedStart: LocalDateTime,
        proposedEnd: LocalDateTime?,
        note: String?
    ) {
        val whenText = proposedStart.format(chatDateTimeFormatter) +
            (proposedEnd?.let { " – " + it.format(chatTimeFormatter) } ?: "")
        val content = buildString {
            append("🔁 Change requested for \"${event.title}\" → ")
            append(whenText)
            if (!note.isNullOrBlank()) append("\n“$note”")
        }
        messageRepository.sendMessage(
            com.coparently.app.domain.model.Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderId,
                senderName = senderName,
                content = content,
                sentAtMillis = System.currentTimeMillis(),
                messageType = com.coparently.app.domain.model.MessageType.EVENT_LINK,
                attachments = listOf(event.id),
                status = com.coparently.app.domain.model.MessageSendStatus.SENDING
            )
        )
    }

    private companion object {
        val chatDateTimeFormatter: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")
        val chatTimeFormatter: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}
