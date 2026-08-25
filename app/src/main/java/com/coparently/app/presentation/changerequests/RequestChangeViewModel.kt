package com.coparently.app.presentation.changerequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.activity.ActivityAnnouncement
import com.coparently.app.domain.activity.ActivityAnnouncer
import com.coparently.app.domain.activity.ActivityEntityType
import com.coparently.app.domain.activity.ActivityKind
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

    /**
     * The request is **recorded**, which is not the same as delivered (CQ-20).
     *
     * It used to be called `Sent`. `ChangeRequestRepositoryImpl.publish` catches every failure
     * and returns, leaving `syncedToFirestore = false` for the outbox to retry, so this state is
     * reached whether or not the write reached Firestore. The screen pops on it either way — the
     * honest report of what happened is the queued chip on the request's card, not this name.
     */
    data object Saved : RequestChangeUiState
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
    private val activityAnnouncer: ActivityAnnouncer
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
     * Creates and sends the change request to the paired co-parent, and announces it.
     *
     * The announcement no longer depends on where the request was started from. It used to be
     * posted only when a `conversationId` was passed — that is, only when the request came from
     * the chat screen — so a change proposed from the calendar reached the thread silently, or
     * rather did not reach it at all. `ActivityAnnouncer` resolves the pair's thread itself, from
     * the two uids, so there is nothing left for a caller to supply or to forget.
     */
    fun submit(
        event: Event,
        proposedStart: LocalDateTime,
        proposedEnd: LocalDateTime?,
        note: String?
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
                // The card is now a payload the *reader* renders in their own language, posted
                // by the one announcer every other change goes through. It used to be a sentence
                // this method built by concatenating hardcoded English — on a Russian phone, an
                // English card.
                //
                // `entityId` is the **event's** id, not the request's, and that is deliberate:
                // `ChangeRequestHighlight.forEvent` resolves a tap to the newest request the
                // reader can still act on, and one event collects several requests over its life.
                // Linking to a particular request would point at whichever one happened to be
                // newest when the card was posted.
                activityAnnouncer.announce(
                    announcement = ActivityAnnouncement(
                        kind = ActivityKind.CHANGE_REQUESTED,
                        entityType = ActivityEntityType.CHANGE_REQUEST,
                        entityId = event.id,
                        title = event.title,
                        whenIso = proposedStart.toString()
                    ),
                    senderName = user.name
                )
                _uiState.value = RequestChangeUiState.Saved
            } catch (
                // Firestore/network failures surface as a form error, not a crash
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                _uiState.value =
                    RequestChangeUiState.Error(e.message ?: "Failed to send the change request")
            }
        }
    }

}
