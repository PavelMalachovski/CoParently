package com.coparently.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.HandoverCalculator
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Kind of change surfaced on the home dashboard.
 */
enum class ActivityKind { EVENT_CREATED, EVENT_UPDATED, PICKUP_CONFIRMED, CHANGE_REQUESTED }

/**
 * One entry in the "recent changes the co-parent made" feed.
 */
data class ActivityItem(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val timestamp: LocalDateTime,
    val eventId: String,
    val isChangeRequest: Boolean
)

/** This month's shared spending total. */
data class MonthSpend(val total: Double, val currency: String)

/**
 * The two repositories [HomeViewModel.monthSpend] is derived from, bundled into one
 * constructor-injected value.
 *
 * [HomeViewModel] already sat at the constructor-parameter limit before the currency fallback
 * (finding 4) needed [PreferencesRepository] alongside the existing [ExpenseRepository] — both
 * of which only ever feed `monthSpend`. Bundling them keeps the real dependency count visible
 * (no `@Suppress`, no widened detekt threshold) while still giving `monthSpend` everything it
 * needs.
 */
data class MonthSpendDependencies @Inject constructor(
    val expenseRepository: ExpenseRepository,
    val preferencesRepository: PreferencesRepository
)

/**
 * ViewModel for the home dashboard. Surfaces the at-a-glance co-parenting state:
 * the next custody handover, the next few events, this month's spend, unread
 * messages, and the recent changes the *other* parent made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    eventRepository: EventRepository,
    changeRequestRepository: ChangeRequestRepository,
    custodyModelRepository: CustodyModelRepository,
    monthSpendDependencies: MonthSpendDependencies,
    messageRepository: MessageRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    /** App-wide default currency, used when the month has no expenses to take one from. */
    private val defaultCurrency: StateFlow<SupportedCurrency> =
        monthSpendDependencies.preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)

    private val _partnerId = MutableStateFlow<String?>(null)
    private val _paired = MutableStateFlow(false)
    private val _userId = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val user = userRepository.getCurrentUser()
            _userId.value = userRepository.getCurrentUserId().orEmpty()
            _partnerId.value = user?.partnerId?.takeIf { it.isNotEmpty() }
            _paired.value = _partnerId.value != null
        }
    }

    /** Whether the user has a paired co-parent (drives the empty state copy). */
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    /** Next custody handover, or null when no custody model is configured. */
    val nextHandover: StateFlow<HandoverInfo?> = custodyModelRepository.getActiveModel()
        .map { model -> model?.let { HandoverCalculator.nextHandoverFrom(it, LocalDate.now()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The next [MAX_UPCOMING] events starting from now (private events included). */
    val upcomingEvents: StateFlow<List<Event>> = eventRepository
        .getEventsByDateRange(LocalDateTime.now(), LocalDateTime.now().plusDays(LOOKAHEAD_DAYS))
        .map { events ->
            val now = LocalDateTime.now()
            events.filter { it.startDateTime >= now }
                .sortedBy { it.startDateTime }
                .take(MAX_UPCOMING)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** This calendar month's total spend across all shared expenses. */
    val monthSpend: StateFlow<MonthSpend> = combine(
        monthSpendDependencies.expenseRepository.getAllExpenses(),
        defaultCurrency
    ) { expenses, fallbackCurrency ->
        val month = LocalDate.now()
        val inMonth = expenses.filter {
            it.date.year == month.year && it.date.month == month.month
        }
        MonthSpend(
            total = inMonth.sumOf { it.amount },
            currency = inMonth.firstOrNull()?.currency ?: fallbackCurrency.code
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        MonthSpend(0.0, SupportedCurrency.DEFAULT.code)
    )

    /** Total unread messages across all conversations. */
    val unreadCount: StateFlow<Int> = _userId
        .flatMapLatest { id ->
            if (id.isEmpty()) {
                flowOf(0)
            } else {
                messageRepository.getConversations(id).map { convs -> convs.sumOf { it.unreadCount } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /**
     * Up to [MAX_ITEMS] most recent changes made by the co-parent, newest first.
     */
    val recentChanges: StateFlow<List<ActivityItem>> = combine(
        eventRepository.getAllEvents(),
        changeRequestRepository.getAllChangeRequests(),
        _partnerId
    ) { events, changeRequests, partnerId ->
        if (partnerId == null) return@combine emptyList()

        val eventItems = events
            .filter { !it.isPrivate && it.lastModifiedBy == partnerId }
            .map { it.toActivityItem() }

        val requestItems = changeRequests
            .filter { it.requestedBy == partnerId }
            .map { request ->
                ActivityItem(
                    id = "cr_${request.id}",
                    kind = ActivityKind.CHANGE_REQUESTED,
                    title = request.eventTitle,
                    timestamp = request.createdAt,
                    eventId = request.eventId,
                    isChangeRequest = true
                )
            }

        (eventItems + requestItems)
            .sortedByDescending { it.timestamp }
            .take(MAX_ITEMS)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    private fun Event.toActivityItem(): ActivityItem {
        val kind = when {
            pickupConfirmedBy != null && pickupConfirmedAt != null &&
                Duration.between(pickupConfirmedAt, updatedAt).abs() <= NEAR_THRESHOLD ->
                ActivityKind.PICKUP_CONFIRMED
            Duration.between(createdAt, updatedAt).abs() <= NEAR_THRESHOLD ->
                ActivityKind.EVENT_CREATED
            else -> ActivityKind.EVENT_UPDATED
        }
        return ActivityItem(
            id = "ev_${id}_$updatedAt",
            kind = kind,
            title = title,
            timestamp = updatedAt,
            eventId = id,
            isChangeRequest = false
        )
    }

    private companion object {
        const val MAX_ITEMS = 5
        const val MAX_UPCOMING = 3
        const val LOOKAHEAD_DAYS = 60L
        const val STOP_TIMEOUT_MS = 5000L
        val NEAR_THRESHOLD: Duration = Duration.ofSeconds(2)
    }
}
