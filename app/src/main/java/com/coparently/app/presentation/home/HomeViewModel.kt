package com.coparently.app.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.chat.ChatReadState
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.custody.CustodyResolver
import com.coparently.app.domain.custody.DayOverride
import com.coparently.app.domain.custody.HandoverCalculator
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.expenses.CurrencyBalance
import com.coparently.app.domain.expenses.calculateExpenseBalancesByCurrency
import com.coparently.app.domain.home.HomeWeek
import com.coparently.app.domain.home.WeekEntry
import com.coparently.app.domain.model.CustodyModel
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
 * What the home page should draw.
 *
 * A sealed state rather than the scatter of independent flows the screen used to recombine
 * itself: "is there a co-parent" decided whether *one* card appeared, and every other section
 * rendered regardless — so an unpaired account got a handover hero with nothing to hand over,
 * two stat tiles reading zero, an empty week and an empty changes feed, arranged around the
 * small card that says the thing that would fill them. The screen is now told what to draw.
 */
sealed interface HomeUiState {

    /**
     * There is no co-parent yet — or this device does not know whether there is one.
     *
     * The page's *content* is then a short explanation and one button. The gear and the bottom
     * bar stay: removing navigation would strand the user, because the calendar and the child's
     * details are worth having alone and Settings is where the pairing screen lives.
     */
    data object AskForCoParent : HomeUiState

    /**
     * The paired dashboard, in the order spec §3 fixes: the next handover, the child's week,
     * the co-parent's recent changes, contacts, and this month's spend last.
     *
     * @property partner The linked co-parent, used to name the changes feed.
     * @property nextHandover When the child next changes hands, or null with no custody model.
     * @property week The child's next seven days, each row naming the parent whose day it
     *   falls on.
     * @property recentChanges What the co-parent changed, newest first.
     * @property monthSpend This month's spending, one subtotal per currency.
     * @property monthBalances This month's settle-up position, per currency.
     * @property unreadCount Unread messages from the co-parent.
     */
    data class Dashboard(
        val partner: PartnerSummary?,
        val nextHandover: HandoverInfo?,
        val week: List<WeekEntry>,
        val recentChanges: List<ActivityItem>,
        val monthSpend: MonthSpend,
        val monthBalances: List<CurrencyBalance>,
        val unreadCount: Int
    ) : HomeUiState
}

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
    private val partner: StateFlow<PartnerSummary?> = pairingState
        .map { (it as? PairingState.Paired)?.partner }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Whether a co-parent is linked. Driven by the pairing repository's realtime state, so
     * the CTA disappears the moment the other parent accepts — without the user reopening
     * the screen. It now gates the whole page rather than one card; see [uiState].
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
     *
     * That reasoning got *stronger* when this began gating the page: the cost of being wrong
     * one way is a paired user seeing the pairing card for a frame, and the cost of being wrong
     * the other way is an unpaired user seeing a handover with nothing to hand over, two tiles
     * reading zero and two empty feeds — the hollow screen this package exists to remove.
     */
    private val paired: StateFlow<Boolean> = pairingState
        .map { it is PairingState.Paired }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * The agreed pattern and the one-off swaps, as one subscription.
     *
     * Both the hero and the week ask "whose day is this", so they share the two Room flows
     * rather than opening them twice. It also makes it structurally impossible for the two
     * sections of the same screen to answer that question differently.
     */
    private val custody: StateFlow<Pair<CustodyModel?, Map<String, DayOverride>>> = combine(
        custodyModelRepository.getActiveModel(),
        custodyModelRepository.observeDayOverrides()
    ) { model, overrides ->
        model to overrides
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null to emptyMap())

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
    private val nextHandover: StateFlow<HandoverInfo?> = custody
        .map { (model, overrides) ->
            model?.let { HandoverCalculator.nextHandoverFrom(it, LocalDate.now(), overrides) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The child's week: every event in the next [HomeWeek.DAYS] days, each row naming the parent
     * whose day it falls on.
     *
     * This is the screen's centre of gravity now, so it is not capped: it used to show the next
     * three events under a header that said "this week", which on a busy Monday meant the week
     * ended on Tuesday with nothing saying so. A week of a child's events is a list a parent can
     * read.
     *
     * The window arithmetic, the private-event filter and the collision-free keys live in the
     * pure [HomeWeek] so they can be tested without a clock or a repository. Custody is resolved
     * through [CustodyResolver], the same lookup the calendar grid uses.
     */
    private val week: StateFlow<List<WeekEntry>> = combine(
        eventRepository.getEventsByDateRange(
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(HomeWeek.DAYS)
        ),
        custody,
        _userId
    ) { events, (model, overrides), userId ->
        HomeWeek.of(
            events = events,
            now = LocalDateTime.now(),
            userId = userId,
            // No legacy fallback, deliberately: this ViewModel has never had one — the handover
            // hero above resolves from the model and the swaps alone — and adding a Room DAO
            // here to answer for accounts that never migrated off `custody_schedules` would put
            // a second custody lookup on the screen. An unanswered day leaves the row's parent
            // unknown, which the row renders as no parent rather than as a guess.
            custodyFor = CustodyResolver.resolver(model, overrides, legacy = { null })
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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
    private val monthSpend: StateFlow<MonthSpend> = combine(
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
    private val monthBalances: StateFlow<List<CurrencyBalance>> = combine(
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
    private val unreadCount: StateFlow<Int> = combine(_userId, _partnerId) { userId, partnerId ->
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
    private val recentChanges: StateFlow<List<ActivityItem>> = combine(
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
     * The paired dashboard's own content, kept whole so [uiState] has one thing to wrap.
     *
     * Combined from flows that each carry their own initial value rather than from bare
     * repository flows: `combine` emits nothing until every source has, so bare sources would
     * hold the whole page back until the slowest Room query answered — and what the user would
     * be looking at meanwhile is [HomeUiState.AskForCoParent], i.e. a paired parent being asked
     * to pair. Each source seeding itself keeps that from happening.
     */
    private val dashboard: StateFlow<HomeUiState.Dashboard> = combine(
        combine(partner, nextHandover, week) { coParent, handover, weekRows ->
            Triple(coParent, handover, weekRows)
        },
        combine(monthSpend, monthBalances, unreadCount) { spend, balances, unread ->
            Triple(spend, balances, unread)
        },
        recentChanges
    ) { (coParent, handover, weekRows), (spend, balances, unread), changes ->
        HomeUiState.Dashboard(
            partner = coParent,
            nextHandover = handover,
            week = weekRows,
            recentChanges = changes,
            monthSpend = spend,
            monthBalances = balances,
            unreadCount = unread
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY_DASHBOARD)

    /**
     * What the home page draws: the pairing invitation, or the dashboard.
     *
     * One value rather than eight flows the composable recombined. The screen used to decide
     * for itself that "unpaired" meant "add a card", which is why every other section kept
     * rendering — the hero, the tiles, the week and the changes feed were all still there,
     * all empty, because nothing had told them not to be.
     */
    val uiState: StateFlow<HomeUiState> = combine(paired, dashboard) { isPaired, content ->
        if (isPaired) content else HomeUiState.AskForCoParent
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        HomeUiState.AskForCoParent
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
        /**
         * What the dashboard holds before any repository has answered. Only ever visible to a
         * paired account, and only for the frame or two the Room queries take.
         */
        val EMPTY_DASHBOARD = HomeUiState.Dashboard(
            partner = null,
            nextHandover = null,
            week = emptyList(),
            recentChanges = emptyList(),
            monthSpend = MonthSpend(listOf(CurrencyAmount(SupportedCurrency.DEFAULT.code, 0.0))),
            monthBalances = emptyList(),
            unreadCount = 0
        )

        const val MAX_ITEMS = 5
        const val STOP_TIMEOUT_MS = 5000L
        const val TAG = "HomeViewModel"
        val NEAR_THRESHOLD: Duration = Duration.ofSeconds(2)
    }
}
