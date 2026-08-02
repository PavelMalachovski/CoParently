package com.coparently.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.HandoverCalculator
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

/** A per-currency subtotal of this month's spending. */
data class CurrencyAmount(val currency: String, val amount: Double)

/**
 * This month's shared spending, kept split by currency. The app does no FX conversion, so
 * amounts in different currencies must never be added into one figure — [byCurrency] carries a
 * separate subtotal for each, largest first.
 */
data class MonthSpend(val byCurrency: List<CurrencyAmount>)

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
 * The two repositories that describe this account's identity and its co-parent link,
 * bundled into one constructor-injected value for the same reason as
 * [MonthSpendDependencies]: [HomeViewModel] was already at the constructor-parameter
 * limit, and task 9 (the realtime pairing CTA) needed [PairingRepository] alongside the
 * existing [UserRepository] — [UserRepository] feeds `_userId`/`_partnerId`, [PairingRepository]
 * feeds [HomeViewModel.paired], and neither is used anywhere else in the class. Bundling
 * keeps the real dependency count visible (no `@Suppress`, no widened detekt threshold)
 * rather than hiding it behind a wider limit.
 *
 * Unlike [MonthSpendDependencies], the two repositories here don't feed one shared
 * computed value — they feed two independent flows. The grouping is by usage locality
 * (both are otherwise-unused-elsewhere, single-consumer dependencies), not by shared
 * purpose; don't take this as a precedent for bundling unrelated repositories together
 * more broadly.
 */
data class HomeIdentityDependencies @Inject constructor(
    val userRepository: UserRepository,
    val pairingRepository: PairingRepository
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
    homeIdentityDependencies: HomeIdentityDependencies
) : ViewModel() {

    /** App-wide default currency, used when the month has no expenses to take one from. */
    private val defaultCurrency: StateFlow<SupportedCurrency> =
        monthSpendDependencies.preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)

    private val _partnerId = MutableStateFlow<String?>(null)
    private val _userId = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val userRepository = homeIdentityDependencies.userRepository
            val user = userRepository.getCurrentUser()
            _userId.value = userRepository.getCurrentUserId().orEmpty()
            _partnerId.value = user?.partnerId?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Whether a co-parent is linked. Driven by the pairing repository's realtime state, so
     * the CTA disappears the moment the other parent accepts — without the user reopening
     * the screen.
     *
     * [PairingState.Loading] (the initial state, and what the repository falls back to if its
     * Firestore listener fails permanently) is treated as "not paired" here, i.e. this card
     * shows. Note this is not the user's only route to pairing — Settings has its own
     * unconditional "Co-Parent Pairing" entry (`SettingsScreen`, wired in `NavGraph`) that
     * works regardless of this flow, so a stuck `Loading` state never makes pairing
     * unreachable app-wide. The choice below is scoped to *this card* only: showing it to an
     * already-paired user is a one-frame cosmetic glitch that self-corrects on the next
     * snapshot, while hiding it from an unpaired user silently drops Home's primary, most
     * visible invitation to pair — with nothing on this screen hinting that anything is
     * wrong or that they should look in Settings instead. Between an occasional redundant
     * card and a silently missing primary CTA, the former is the smaller cost, so "not
     * paired" is what this flow defaults to while the real answer is unknown.
     */
    val paired: StateFlow<Boolean> = homeIdentityDependencies.pairingRepository.observePairingState()
        .map { it is PairingState.Paired }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

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

    /** This calendar month's spend, one subtotal per currency (no cross-currency summing). */
    val monthSpend: StateFlow<MonthSpend> = combine(
        monthSpendDependencies.expenseRepository.getAllExpenses(),
        defaultCurrency
    ) { expenses, fallbackCurrency ->
        val month = LocalDate.now()
        val inMonth = expenses.filter {
            it.date.year == month.year && it.date.month == month.month
        }
        val byCurrency = inMonth.groupBy { it.currency }
            .map { (currency, group) -> CurrencyAmount(currency, group.sumOf { it.amount }) }
            .sortedByDescending { it.amount }
        // Keep a zero in the app's default currency when the month is empty, so the tile still
        // renders a figure instead of blank.
        MonthSpend(byCurrency.ifEmpty { listOf(CurrencyAmount(fallbackCurrency.code, 0.0)) })
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        MonthSpend(listOf(CurrencyAmount(SupportedCurrency.DEFAULT.code, 0.0)))
    )

    /**
     * Number of conversations with activity the user has not opened yet.
     *
     * Derived from [com.coparently.app.domain.model.Conversation.lastMessageAtMillis] versus
     * this user's own [com.coparently.app.domain.model.Conversation.lastReadAt] mark — there
     * is no stored counter any more (see `unreadCount`'s removal from the domain model). This
     * counts conversations, not individual messages: a precise per-message count would need
     * each conversation's message list, which this dashboard tile does not otherwise load.
     * Chat is 1:1 today, so in practice this is 0 or 1.
     */
    val unreadCount: StateFlow<Int> = _userId
        .flatMapLatest { id ->
            if (id.isEmpty()) {
                flowOf(0)
            } else {
                messageRepository.getConversations(id).map { convs ->
                    convs.count { conv -> (conv.lastMessageAtMillis ?: 0L) > (conv.lastReadAt[id] ?: 0L) }
                }
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
