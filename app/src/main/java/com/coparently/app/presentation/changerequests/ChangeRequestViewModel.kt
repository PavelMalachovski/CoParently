package com.coparently.app.presentation.changerequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.domain.usecase.EventUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * ViewModel for the change-requests inbox: incoming and outgoing requests
 * plus accept/decline/cancel actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChangeRequestViewModel @Inject constructor(
    private val changeRequestRepository: ChangeRequestRepository,
    private val eventRepository: EventRepository,
    private val eventUseCases: EventUseCases,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _currentUserId = MutableStateFlow("")
    val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // `changeRequests` starts at `initialValue = emptyList()` before Room's flow has emitted
    // even once, and that placeholder is structurally identical to a real, confirmed-empty
    // result — a genuinely empty inbox also settles on `emptyList()`. A reader that needs to
    // know "has the real data arrived yet" (the chat-card deep link's highlight/"already
    // closed" decision in ChangeRequestsScreen) cannot tell the two apart from the list value
    // alone, so this flips once the underlying flow has produced its first real emission,
    // whatever that emission is.
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    val changeRequests: StateFlow<List<ChangeRequest>> =
        changeRequestRepository.getAllChangeRequests()
            .onEach { _hasLoaded.value = true }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val pendingIncomingCount: StateFlow<Int> = _currentUserId
        .flatMapLatest { userId ->
            changeRequestRepository.getPendingIncomingCount(userId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    init {
        viewModelScope.launch {
            userRepository.getCurrentUser()?.let { user ->
                _currentUserId.value = user.id
            }
        }
        // Mirrors remote requests into Room while this ViewModel is alive.
        viewModelScope.launch {
            changeRequestRepository.syncWithFirestore()
        }
    }

    /**
     * Accepts an incoming request: moves the event to the proposed time
     * (via the update use case so reminders are rescheduled) and marks the
     * request accepted, notifying the requester.
     */
    fun accept(requestId: String) {
        viewModelScope.launch {
            val request = changeRequestRepository.getChangeRequestById(requestId) ?: return@launch
            val event = eventRepository.getEventById(request.eventId)
            if (event == null) {
                _errorMessage.value = "The event for this request no longer exists"
                return@launch
            }
            val result = eventUseCases.updateEvent(
                event.copy(
                    startDateTime = request.proposedStartDateTime,
                    endDateTime = request.proposedEndDateTime,
                    updatedAt = LocalDateTime.now(),
                    lastModifiedBy = _currentUserId.value.takeIf { it.isNotEmpty() }
                        ?: event.lastModifiedBy
                )
            )
            result.fold(
                onSuccess = {
                    changeRequestRepository.updateStatus(requestId, ChangeRequestStatus.ACCEPTED)
                },
                onFailure = { e ->
                    _errorMessage.value = e.message ?: "Failed to apply the change"
                }
            )
        }
    }

    /** Declines an incoming request; the event stays unchanged. */
    fun decline(requestId: String) {
        viewModelScope.launch {
            changeRequestRepository.updateStatus(requestId, ChangeRequestStatus.DECLINED)
        }
    }

    /** Withdraws an outgoing pending request. */
    fun cancel(requestId: String) {
        viewModelScope.launch {
            changeRequestRepository.updateStatus(requestId, ChangeRequestStatus.CANCELLED)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
