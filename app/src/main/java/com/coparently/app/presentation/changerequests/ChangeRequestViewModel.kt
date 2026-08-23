package com.coparently.app.presentation.changerequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.CustodyProposal
import com.coparently.app.domain.custody.DayOverride
import com.coparently.app.domain.custody.DayOverrideTransition
import com.coparently.app.domain.custody.DaySwap
import com.coparently.app.domain.custody.DaySwapInbox
import com.coparently.app.domain.events.EventAcceptanceTransition
import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.domain.usecase.EventUseCases
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * ViewModel for the inbox: event change requests, one-off day swaps, and the actions on both.
 *
 * The two live together because the inbox is one screen and one back-stack entry — a parent
 * answering "what is waiting for me" should not have to look in two places. They stay separate
 * *types* all the way down, though: a change request points at an event and moves its times, a
 * swap points at a date and moves nothing but who has it. See `DayOverride` for why folding one
 * into the other was rejected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // one inbox, two request kinds, and the repositories behind each
@HiltViewModel
class ChangeRequestViewModel @Inject constructor(
    private val changeRequestRepository: ChangeRequestRepository,
    private val eventRepository: EventRepository,
    private val eventUseCases: EventUseCases,
    private val userRepository: UserRepository,
    private val custodyModelRepository: CustodyModelRepository,
    parentsSource: ParentsSource
) : ViewModel() {

    /**
     * Signed-in parent and paired co-parent, for naming whoever offered a swap.
     *
     * From `ParentsSource` — the single place that joins the signed-in user's Room row to the
     * co-parent's `PartnerSummary`. Never from `getAllUsers()`, which only ever holds a row for
     * the signed-in user and, on a device where more than one account has signed in over time,
     * holds rows for accounts paired with nobody.
     */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Parents())

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

    /**
     * The one-off day swaps worth showing, soonest first.
     *
     * Bounded by the date rather than by the status: nothing deletes an answered swap, because
     * the answer is the record the parent who offered it reads, and filtering on "still pending"
     * would make a refusal vanish without them ever being told. See [DaySwapInbox].
     *
     * `LocalDate.now()` is read per emission rather than once: this flow outlives midnight on a
     * phone left open, and a boundary computed at subscription time would keep yesterday's swaps
     * in the list until the process died.
     */
    val daySwaps: StateFlow<List<DaySwap>> = custodyModelRepository.observeDayOverrides()
        .map { overrides -> DaySwapInbox.visible(overrides, LocalDate.now()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Events the co-parent created for this parent that are still waiting on an answer.
     *
     * Read from `getAllEvents`, which is deliberately **not** acceptance-filtered — the calendar
     * queries are, and this is the one screen that has to see what they hide. Without it a pending
     * event would be invisible on both phones, which is worse than not having the feature.
     *
     * Only events this parent did not create appear: `EventAcceptanceTransition` and the
     * repository both refuse a creator deciding their own, so offering the buttons here would
     * promise an action that is rejected. The creator sees theirs on the event list instead.
     */
    val eventsAwaitingMe: StateFlow<List<Event>> = combine(
        eventRepository.getAllEvents(),
        _currentUserId
    ) { events, uid ->
        if (uid.isEmpty()) {
            emptyList()
        } else {
            events.filter { it.acceptance.isPending && it.createdByFirebaseUid != uid }
                .sortedBy { it.startDateTime }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
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

    /**
     * Takes up the co-parent's offer of [date]. From then on the calendar reads the swap instead
     * of the pattern — nothing folds it back into the agreed schedule, which is what keeps it
     * one-off.
     */
    fun acceptSwap(date: LocalDate) = decideSwap(date) { current, uid, now ->
        DayOverrideTransition.accept(current, date.toString(), uid, now)
    }

    /** Turns the co-parent's offer of [date] down. Whose day it is does not change. */
    fun declineSwap(date: LocalDate) = decideSwap(date) { current, uid, now ->
        DayOverrideTransition.decline(current, date.toString(), uid, now)
    }

    /**
     * Applies a decision to the shared document.
     *
     * The transition runs against whatever the document holds *now*, not against the list this
     * screen last collected: both parents write to one document, and a held snapshot would
     * silently drop whatever the other one did in between.
     *
     * A refusal is surfaced, not swallowed. `DayOverrideTransition` refuses a parent deciding
     * their own offer and `firestore.rules` refuses it again; either way the parent has to be
     * told, because a button that looks like it worked is worse than one that says it did not.
     */
    private fun decideSwap(
        date: LocalDate,
        transition: (Map<String, DayOverride>, String, String) -> Result<Map<String, DayOverride>>
    ) {
        viewModelScope.launch {
            val uid = _currentUserId.value.takeIf { it.isNotEmpty() }
                ?: userRepository.getCurrentUserId()
                ?: return@launch
            val now = LocalDateTime.now().toString()
            custodyModelRepository.applyDayOverrides(date.toString()) { current ->
                transition(current, uid, now)
            }.onFailure { e ->
                _errorMessage.value = e.message ?: "The swap could not be answered"
            }
        }
    }

    /**
     * The custody-pattern proposal this parent must answer, or null when there is none.
     *
     * Only the co-parent's proposal surfaces here — a parent never decides their own (the
     * transition and `firestore.rules` both refuse it). The proposer sees theirs as the
     * calendar's preview overlay and a "waiting" banner instead.
     */
    val pendingProposal: StateFlow<CustodyProposal?> = combine(
        custodyModelRepository.observeShared(),
        _currentUserId
    ) { shared, uid ->
        shared?.proposal?.takeIf { uid.isNotEmpty() && it.proposedBy != uid }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    /** Accepts the co-parent's pending custody proposal; it becomes the agreed pattern. */
    fun acceptProposal() {
        viewModelScope.launch {
            custodyModelRepository.acceptProposal().onFailure { e ->
                _errorMessage.value = e.message ?: "The proposal could not be accepted"
            }
        }
    }

    /** Declines the co-parent's pending custody proposal; the agreed pattern is untouched. */
    fun declineProposal() {
        viewModelScope.launch {
            custodyModelRepository.declineProposal().onFailure { e ->
                _errorMessage.value = e.message ?: "The proposal could not be declined"
            }
        }
    }

    /** Takes up an event the co-parent created for this parent. It then enters both calendars. */
    fun acceptEvent(eventId: String) = decideEvent(eventId, accept = true)

    /**
     * Turns one down. It stays `DECLINED` rather than being deleted: the creator needs to know
     * the answer was no, and an event that silently vanished would read as a bug.
     */
    fun declineEvent(eventId: String) = decideEvent(eventId, accept = false)

    /**
     * Applies a decision through [EventAcceptanceTransition] and saves it.
     *
     * The event is re-read immediately before deciding rather than taken from the list this
     * screen last collected: the co-parent may have edited or withdrawn it in between, and a held
     * snapshot would write that edit back out along with the answer.
     *
     * A refusal is surfaced, not swallowed — the transition refuses a creator deciding their own
     * event and a decision on an already-answered one, and both are things the parent has to see
     * rather than watch a button do nothing.
     */
    private fun decideEvent(eventId: String, accept: Boolean) {
        viewModelScope.launch {
            val fresh = eventRepository.getEventById(eventId) ?: return@launch
            val uid = _currentUserId.value.takeIf { it.isNotEmpty() }
                ?: userRepository.getCurrentUserId()
                ?: return@launch
            val now = LocalDateTime.now()
            val result = if (accept) {
                EventAcceptanceTransition.accept(fresh, uid, now)
            } else {
                EventAcceptanceTransition.decline(fresh, uid, now)
            }
            result.fold(
                onSuccess = { eventRepository.updateEvent(it) },
                onFailure = { e -> _errorMessage.value = e.message ?: "Could not answer that" }
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
