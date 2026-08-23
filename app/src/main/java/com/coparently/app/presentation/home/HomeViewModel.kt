package com.coparently.app.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.chat.ChatReadState
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.custody.HandoverCalculator
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.expenses.CurrencyBalance
import com.coparently.app.domain.expenses.calculateExpenseBalancesByCurrency
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
 * Unlike [MonthSpendDependencies], the dependencies here don't feed one shared
 * computed value — they feed independent flows. The grouping is by usage locality
 * (each is an otherwise-unused-elsewhere, single-consumer dependency), not by shared
 * purpose; don't take this as a precedent for bundling unrelated repositories together
 * more broadly.
 *
 * [parentsSource] joined them rather than becoming a seventh constructor parameter, for the
 * same reason the other two are here and because "who are the two parents" is precisely what
 * this bundle is about.
 */
data class HomeIdentityDependencies @Inject constructor(
    val userRepository: UserRepository,
    val pairingRepository: PairingRepository,
    val parentsSource: ParentsSource
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
     * Realtime pairing state, shared by [paired] and [partner] so one Firestore listener
     * feeds both instead of two.
     */
    private val pairingState = homeIdentityDependencies.pairingRepository.observePairingState()

    /**
     * The linked co-parent, or null while unpaired. Names the activity feed ("Alex changed")
     * and the chat tile, so the dashboard talks about a person rather than "the co-parent".
     */
    val partner: StateFlow<PartnerSummary?> = pairingState
        .map { (it as? PairingState.Paired)?.partner }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

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
    val paired: StateFlow<Boolean> = pairingState
        .map { it is PairingState.Paired }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * Next custody handover, or null when no custody model is configured.
     *
     * **Computed with the one-off swaps, not from the pattern alone.** A swap both creates a
     * handover on the day it moves and removes the one it displaced, and a calculator that cannot
     * see them fails at neither compile time nor run time — it simply tells this hero a date that
     * is wrong, on exactly the days a swap touched, while the calendar beside it paints the swap
     * correctly. This is the flow that failure would have shown up in.
     *
     * The hero says which **day** the child changes hands and to whom, never at what hour. There
     * is no handover time anywhere in the schema — `CustodyModel` carries days, never hours — so
     * an hour here would be invented, and a separated parent would plan around it.
     */
    val nextHandover: StateFlow<HandoverInfo?> = combine(
        custodyModelRepository.getActiveModel(),
        custodyModelRepository.observeDayOverrides()
    ) { model, overrides ->
        model?.let { HandoverCalculator.nextHandoverFrom(it, LocalDate.now(), overrides) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The next [MAX_UPCOMING] events starting from now, within [LOOKAHEAD_DAYS] (private
     * events included). The dashboard renders these as the "this week" timeline, so the
     * window is a week rather than the two months the old flat "Upcoming" list looked over —
     * a dentist appointment seven weeks out is not what "this week" promises.
     */
    val upcomingEvents: StateFlow<List<Event>> = eventRepository
        .getEventsByDateRange(LocalDateTime.now(), LocalDateTime.now().plusDays(LOOKAHEAD_DAYS))
        .map { events ->
            val now = LocalDateTime.now()
            events.filter { it.startDateTime >= now }
                .sortedBy { it.startDateTime }
                .take(MAX_UPCOMING)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Expenses dated in the current calendar month. Shared by [monthSpend] and
     * [monthBalances] so the tile's total and the "who owes whom" line under it are always
     * computed from the same set of rows.
     */
    private val monthExpenses = monthSpendDependencies.expenseRepository.getAllExpenses()
        .map { expenses ->
            val month = LocalDate.now()
            expenses.filter { it.date.year == month.year && it.date.month == month.month }
        }

    /**
     * Signed-in parent and paired co-parent, for resolving a slot to a name — the handover
     * tile, the timeline and the activity feed all name a parent.
     */
    val parents: StateFlow<Parents> = homeIdentityDependencies.parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Parents())

    /**
     * uid -> slot, needed to attribute who paid what. Empty until the profiles load.
     *
     * Built from [parents], not from `getAllUsers()`: Room stores a `users` row for the
     * signed-in user only, so the old map could never contain both parents and
     * `ExpenseBalance.splitKnown` was false on every device — which silently emptied the
     * settle-up line this dashboard filters on (see [monthBalances]). See
     * `ExpenseViewModel.roleByUid` for the full account.
     */
    private val roleByUid = parents.map { it.roleByUid }

    /** This calendar month's spend, one subtotal per currency (no cross-currency summing). */
    val monthSpend: StateFlow<MonthSpend> = combine(
        monthExpenses,
        defaultCurrency
    ) { expenses, fallbackCurrency ->
        val byCurrency = expenses.groupBy { it.currency }
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
     * This month's settle-up position, one entry per currency — the same figures the Expenses
     * screen shows, surfaced on the spend tile so the dashboard answers "are we square?"
     * without a tab switch.
     *
     * Reuses the pure [calculateExpenseBalancesByCurrency] rather than recomputing, so the two
     * screens cannot disagree about the same month.
     */
    val monthBalances: StateFlow<List<CurrencyBalance>> = combine(
        monthExpenses,
        _userId,
        roleByUid
    ) { expenses: List<Expense>, userId: String, roles: Map<String, String> ->
        calculateExpenseBalancesByCurrency(expenses, userId, roles)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Number of unread **messages** from the co-parent, matching the tile's label.
     *
     * Counted per message by [ChatReadState.unreadCount], from the co-parent thread's own
     * messages against this user's `lastReadAt` mark. The interim version counted
     * *conversations* with activity, which — chat being 1:1 — could only ever render 0 or 1
     * under a label that says messages.
     *
     * Both the id and the flows are derived, not fetched: the conversation id is a pure
     * function of the two uids, so the tile needs no list query. A remote failure inside the
     * repository is already contained there; the [catch] here is the last resort that keeps
     * a Room-level failure from taking down `viewModelScope` and, with it, the process.
     */
    val unreadCount: StateFlow<Int> = combine(_userId, _partnerId) { userId, partnerId ->
        userId to partnerId
    }
        .flatMapLatest { (userId, partnerId) ->
            val conversationId = partnerId?.let { conversationIdOrNull(userId, it) }
            if (conversationId == null) {
                flowOf(0)
            } else {
                combine(
                    messageRepository.observeConversation(conversationId),
                    messageRepository.observeMessages(conversationId)
                ) { conversation, messages ->
                    ChatReadState.unreadCount(messages, userId, conversation?.lastReadAt?.get(userId))
                }
            }
        }
        .catch { e ->
            Log.w(TAG, "Home unread count failed; showing zero", e)
            emit(0)
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

    /**
     * The deterministic conversation id for [userId] and [partnerId], or `null` when the
     * pair cannot form one — an unresolved session (blank uid), or a partner id that
     * somehow equals this user's.
     */
    private fun conversationIdOrNull(userId: String, partnerId: String): String? =
        runCatching { ConversationKey.of(userId, partnerId) }.getOrNull()

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
        const val LOOKAHEAD_DAYS = 7L
        const val STOP_TIMEOUT_MS = 5000L
        const val TAG = "HomeViewModel"
        val NEAR_THRESHOLD: Duration = Duration.ofSeconds(2)
    }
}
