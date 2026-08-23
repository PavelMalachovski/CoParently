package com.coparently.app.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.CustodyChangeAnnouncement
import com.coparently.app.domain.custody.CustodyProposal
import com.coparently.app.domain.custody.CustodyResolver
import com.coparently.app.domain.custody.DayOverride
import com.coparently.app.domain.custody.DayOverrideTransition
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

/** Keeps the shared flows warm across brief unsubscriptions (config changes). */
private const val HANDOVER_STOP_TIMEOUT_MS = 5_000L

/**
 * Why offering a one-off day swap did not work.
 *
 * A code, not a message: a ViewModel has no `Context` and must not acquire one to build
 * user-facing text — the same rule that keeps `GoogleCalendarSyncState.message` on the
 * localization follow-up list rather than being "fixed" with an injected `Context`. The screen
 * resolves it to a string in composable scope.
 */
enum class SwapError {
    /** No co-parent, or this device does not know who it is yet. Nobody could accept. */
    NOT_READY,

    /** The write was refused — by the transition's own rules, or by `firestore.rules`. */
    REFUSED
}

/**
 * ViewModel for calendar screen.
 * Handles custody schedule data, custody model, view mode,
 * parent filtering (You / Both / Co-parent) and event type filters.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val custodyModelRepository: CustodyModelRepository,
    private val encryptedPreferences: EncryptedPreferences,
    parentsSource: ParentsSource
) : ViewModel() {

    /**
     * Signed-in parent and paired co-parent, for resolving a slot to a name. The whole calendar
     * tree — grid, ribbon, agenda card, filters, preview sheet — labels parents from this one
     * value, so no two of them can disagree about who somebody is.
     */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(HANDOVER_STOP_TIMEOUT_MS), Parents())

    /**
     * Active legacy custody schedules, the fallback half of the unified custody lookup.
     *
     * Derived from the DAO flow rather than pushed into a [MutableStateFlow] by a `load…()`
     * method: the Room flow already re-emits on every write, so re-collecting it buys nothing
     * and the old method — called from `init` *and* from pull-to-refresh — left one permanent,
     * uncancelled collector behind per call, each of them recomposing the whole Calendar tab.
     * `Eagerly` because [getCustodyForDate] reads `.value` outside any collection.
     */
    val custodySchedules: StateFlow<List<CustodyScheduleEntity>> =
        custodyScheduleDao.getAllActiveSchedules()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Why a swap could not be offered, or null when nothing is wrong.
     *
     * An enum rather than a message, because a ViewModel has no `Context` and must not acquire
     * one to build a user-facing string — CLAUDE.md's standing rule, and the reason
     * `GoogleCalendarSyncState.message` is still a tracked follow-up.
     */
    private val _swapError = MutableStateFlow<SwapError?>(null)
    val swapError: StateFlow<SwapError?> = _swapError.asStateFlow()

    private val _custodyModel = MutableStateFlow<CustodyModel?>(null)
    val custodyModel: StateFlow<CustodyModel?> = _custodyModel.asStateFlow()

    /**
     * One-off day swaps, keyed by ISO date.
     *
     * Kept beside [custodyModel] rather than folded into it because they are different things:
     * the model is the agreed *pattern*, a swap is a fact about the shared document. The two are
     * joined by [com.coparently.app.domain.custody.CustodyResolver], which is the single place
     * the precedence between them lives — an accepted swap wins, a pending or declined one
     * changes nothing.
     *
     * `Eagerly`, matching [custodySchedules]: [getCustodyForDate] reads `.value` outside any
     * collection, and a lazily-started flow would answer with the pattern alone on the first call.
     */
    val dayOverrides: StateFlow<Map<String, DayOverride>> =
        custodyModelRepository.observeDayOverrides()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The `lastModifiedAt` of the shared-custody change the user last dismissed the banner for,
     * or null if none was ever dismissed. Seeded from [EncryptedPreferences] so an
     * acknowledged change stays acknowledged across process death, and updated in place by
     * [dismissCustodyChange] rather than re-read from disk each time.
     */
    private val dismissedCustodyChangeAt = MutableStateFlow(
        encryptedPreferences.getString(KEY_DISMISSED_CUSTODY_CHANGE_AT)
    )

    /**
     * The remote custody change to tell this user about, or null when there is nothing to say.
     *
     * Custody is last-write-wins with no consent step, so this is what keeps that from being
     * *silent*: [CustodyChangeAnnouncement.toAnnounce] excludes this device's own writes (by
     * uid, from [parents]) and anything already dismissed (by `lastModifiedAt`). It also excludes
     * anything at all until [Parents.loaded] is true — [parents] starts from a synthetic
     * "nobody is known yet" on every fresh subscription, and `CustodyModelRepository`'s own pair
     * resolution is Room-only and reliably faster than `Parents` resolving three Firestore
     * pairing listeners, so the echo of this device's own just-made write can otherwise arrive
     * before its own uid is known — announcing the user's own edit as the co-parent's, exactly
     * the failure this feature must not have, just pointed the other way.
     *
     * `WhileSubscribed`, like the banner below, and not an eager collector: [parents] itself only
     * subscribes to [ParentsSource]'s pairing listener while something is collecting it, and
     * that stream is expensive to hold open (three Firestore listeners — see
     * [ParentsSource.observe]'s own doc). An always-on collector here would keep it attached for
     * this ViewModel's entire lifetime regardless of whether any screen is showing the banner,
     * undoing the exact saving `WhileSubscribed` exists for.
     */
    val custodyChangeAnnouncement: StateFlow<SharedCustody?> = combine(
        custodyModelRepository.observeShared(),
        parents,
        dismissedCustodyChangeAt
    ) { shared, currentParents, dismissedAt ->
        CustodyChangeAnnouncement.toAnnounce(shared, currentParents.me?.uid, currentParents.loaded, dismissedAt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(HANDOVER_STOP_TIMEOUT_MS), null)

    /**
     * The custody pattern one parent has put to the other and nobody has answered yet, or null.
     *
     * PR #47 gave a proposal its own state on the shared document precisely so that saving one
     * changes nobody's pattern until it is accepted. The grid simply did not draw it — so a
     * parent who had proposed a change, and a parent who had one waiting, both saw a calendar
     * that looked entirely settled. This is what lets the grid say "this is what it would
     * become" without saying it already has.
     *
     * Shown to **both** parents, not only to the one who has to answer. The proposer needs to
     * see what they asked for as much as the recipient needs to see what is being asked; and
     * neither reading changes the days, because [getCustodyForDate] deliberately does not
     * consult this.
     *
     * `WhileSubscribed`, matching [custodyChangeAnnouncement] and for its reason: this shares
     * that flow's single Firestore listener, and an eager collector would hold it open for this
     * ViewModel's whole lifetime whether or not a calendar is on screen.
     */
    val pendingProposal: StateFlow<CustodyProposal?> = custodyModelRepository.observeShared()
        .map { it?.proposal }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(HANDOVER_STOP_TIMEOUT_MS), null)

    private val _viewMode = MutableStateFlow(CalendarViewMode.MONTH)
    val viewMode: StateFlow<CalendarViewMode> = _viewMode.asStateFlow()

    private val _displayedMonth = MutableStateFlow(YearMonth.now())

    /** Month the grid is showing. Independent of [selectedDate], which may be absent. */
    val displayedMonth: StateFlow<YearMonth> = _displayedMonth.asStateFlow()

    private val _queryAnchorMonth = MutableStateFlow(YearMonth.now())

    /**
     * Month the event query window is centred on. Distinct from [displayedMonth]: it stays put
     * while the user pages within [CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS] of it, so a
     * settle no longer triggers a fresh query. Chasing the displayed month is what made backward
     * paging drop frames — see the item 8 diagnosis.
     */
    val queryAnchorMonth: StateFlow<YearMonth> = _queryAnchorMonth.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())

    /**
     * The day the user has chosen, or null when none is. Paging to another month clears it,
     * except paging back to today's month, which re-selects today — see [showMonth]. The agenda
     * card under the grid renders only when this is non-null: a card describing a day nobody
     * picked is what the August 2026 baseline found it doing.
     */
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    private val _parentFilter = MutableStateFlow(ParentFilter.BOTH)
    val parentFilter: StateFlow<ParentFilter> = _parentFilter.asStateFlow()

    private val _hiddenEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val hiddenEventTypes: StateFlow<Set<String>> = _hiddenEventTypes.asStateFlow()

    private val _customEventTypes = MutableStateFlow<List<String>>(emptyList())
    val customEventTypes: StateFlow<List<String>> = _customEventTypes.asStateFlow()

    private val _showHolidays = MutableStateFlow(true)
    val showHolidays: StateFlow<Boolean> = _showHolidays.asStateFlow()

    init {
        loadCustodyModel()
        loadFilterPreferences()
    }

    /**
     * Loads the active custody model.
     * This is the preferred method for determining custody.
     *
     * Private and called only from `init`, so its collector is created exactly once and lives
     * for the ViewModel's lifetime — the leak [custodySchedules] used to have needs a second
     * caller, and this has none.
     */
    private fun loadCustodyModel() {
        viewModelScope.launch {
            custodyModelRepository.getActiveModel().collect { model ->
                _custodyModel.value = model
            }
        }
    }

    /**
     * Offers [date] to the other parent, as a one-off swap they have to agree to.
     *
     * The transition is applied to whatever the shared document holds *now*, not to the map this
     * screen last collected: two parents negotiating a day are writing to one document, and a
     * held snapshot would silently drop whatever the other one did in between.
     *
     * `lastModifiedAt` is deliberately not restamped — see
     * [com.coparently.app.data.repository.CustodyModelRepository.applyDayOverrides].
     *
     * Failures are surfaced through [swapError] rather than swallowed: refusing a swap is a real
     * outcome the parent has to see, and this is the one screen where a silent no-op would look
     * exactly like success.
     *
     * @param date The day being offered.
     * @param toParent The slot that would take it — `"mom"` or `"dad"`.
     * @param note Optional free text; blank normalises to none.
     */
    fun offerDaySwap(date: LocalDate, toParent: String, note: String? = null) {
        viewModelScope.launch {
            val myUid = parents.value.me?.uid
            if (myUid == null) {
                _swapError.value = SwapError.NOT_READY
                return@launch
            }
            val iso = date.toString()
            custodyModelRepository.applyDayOverrides(iso) { current ->
                DayOverrideTransition.offer(
                    current = current,
                    date = iso,
                    toParent = toParent,
                    byUid = myUid,
                    atIso = LocalDateTime.now().toString(),
                    note = note
                )
            }.onFailure { _swapError.value = SwapError.REFUSED }
        }
    }

    /** Clears whatever [swapError] is showing, once the screen has shown it. */
    fun clearSwapError() {
        _swapError.value = null
    }

    /**
     * Dismisses the custody-changed banner for the change stamped with [lastModifiedAt].
     *
     * Persisted to [EncryptedPreferences] rather than kept only in memory: a change the user has
     * already acknowledged must not reappear just because the app process died. Keying on the
     * change's own `lastModifiedAt` (instead of, say, a boolean) is what lets the *next* change —
     * a different `lastModifiedAt` — be announced again without a separate "seen" reset.
     */
    fun dismissCustodyChange(lastModifiedAt: String) {
        dismissedCustodyChangeAt.value = lastModifiedAt
        encryptedPreferences.putString(KEY_DISMISSED_CUSTODY_CHANGE_AT, lastModifiedAt)
    }

    /**
     * Restores persisted filter state (hidden types, custom types, holiday toggle).
     */
    private fun loadFilterPreferences() {
        _hiddenEventTypes.value = encryptedPreferences.getString(KEY_HIDDEN_EVENT_TYPES)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        _customEventTypes.value = encryptedPreferences.getString(KEY_CUSTOM_EVENT_TYPES)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        _showHolidays.value = encryptedPreferences.getBoolean(KEY_SHOW_HOLIDAYS, true)
    }

    /**
     * Whose day [date] is: an accepted one-off swap, then the active model, then the legacy
     * schedules.
     *
     * Resolved through [CustodyResolver] rather than by reading the model directly, so this
     * cannot drift from the grid's own lookup. CLAUDE.md already required one custody lookup —
     * reading the legacy rows directly is what once made model-based custody invisible — and a
     * second copy of the precedence would reintroduce the same class of bug with swaps.
     *
     * @param date The date to check
     * @return "mom", "dad", or null
     */
    fun getCustodyForDate(date: LocalDate): String? = CustodyResolver.custodyFor(
        model = _custodyModel.value,
        overrides = dayOverrides.value,
        legacy = { day -> CustodyHelper.getCustodyForDate(day, custodySchedules.value) },
        date = date
    )

    /**
     * Sets the calendar view mode.
     */
    fun setViewMode(mode: CalendarViewMode) {
        _viewMode.value = mode
    }

    /**
     * Selects a day the user actually tapped, and brings the grid to its month — the month grid
     * renders leading and trailing days of the neighbouring months, so a tap can land outside
     * the month on screen.
     */
    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
        moveTo(YearMonth.from(date))
    }

    /**
     * Shows [month], selecting today when it is today's month and clearing the selection
     * otherwise.
     */
    fun showMonth(month: YearMonth) {
        moveTo(month)
        _selectedDate.value = CalendarSelection.forMonth(month, LocalDate.now())
    }

    /** Shows [month] and re-centres the query window only if it has drifted too far. */
    private fun moveTo(month: YearMonth) {
        _displayedMonth.value = month
        _queryAnchorMonth.value = CalendarSelection.reanchor(_queryAnchorMonth.value, month)
    }

    /**
     * Sets which parent's events are visible (You / Both / Co-parent view).
     */
    fun setParentFilter(filter: ParentFilter) {
        _parentFilter.value = filter
    }

    /**
     * Toggles visibility of an event type in the calendar (e.g. hide "school" in December).
     */
    fun toggleEventTypeVisibility(eventType: String) {
        val updated = _hiddenEventTypes.value.toMutableSet().apply {
            if (!add(eventType)) remove(eventType)
        }
        _hiddenEventTypes.value = updated
        encryptedPreferences.putString(KEY_HIDDEN_EVENT_TYPES, updated.joinToString(SEPARATOR))
    }

    /**
     * Adds a user-defined event type. No-op for blank or duplicate names.
     */
    fun addCustomEventType(name: String) {
        val normalized = name.trim().lowercase()
        if (normalized.isBlank() || normalized in DEFAULT_EVENT_TYPES || normalized in _customEventTypes.value) {
            return
        }
        val updated = _customEventTypes.value + normalized
        _customEventTypes.value = updated
        encryptedPreferences.putString(KEY_CUSTOM_EVENT_TYPES, updated.joinToString(SEPARATOR))
    }

    /**
     * Toggles whether Czech public holidays and school vacations are shown.
     */
    fun setShowHolidays(show: Boolean) {
        _showHolidays.value = show
        encryptedPreferences.putBoolean(KEY_SHOW_HOLIDAYS, show)
    }

    /**
     * All event types available for filtering: defaults + user-defined.
     */
    fun allEventTypes(): List<String> = DEFAULT_EVENT_TYPES + _customEventTypes.value

    companion object {
        val DEFAULT_EVENT_TYPES = listOf("general", "medical", "school", "sports", "birthday")
        private const val KEY_HIDDEN_EVENT_TYPES =
            com.coparently.app.data.local.preferences.PreferenceKeys.HIDDEN_EVENT_TYPES
        private const val KEY_CUSTOM_EVENT_TYPES =
            com.coparently.app.data.local.preferences.PreferenceKeys.CUSTOM_EVENT_TYPES
        private const val KEY_SHOW_HOLIDAYS =
            com.coparently.app.data.local.preferences.PreferenceKeys.SHOW_HOLIDAYS
        private const val KEY_DISMISSED_CUSTODY_CHANGE_AT =
            PreferenceKeys.DISMISSED_CUSTODY_CHANGE_AT
        private const val SEPARATOR =
            com.coparently.app.data.local.preferences.PreferenceKeys.LIST_SEPARATOR
    }
}
