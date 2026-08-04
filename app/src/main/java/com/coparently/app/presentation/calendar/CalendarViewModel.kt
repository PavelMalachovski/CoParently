package com.coparently.app.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.HandoverCalculator
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.model.CustodyModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** Keeps the handover flow warm across brief unsubscriptions (config changes). */
private const val HANDOVER_STOP_TIMEOUT_MS = 5_000L

/**
 * ViewModel for calendar screen.
 * Handles custody schedule data, custody model, view mode,
 * parent filtering (You / Both / Co-parent) and event type filters.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val custodyModelRepository: CustodyModelRepository,
    private val encryptedPreferences: EncryptedPreferences
) : ViewModel() {

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

    private val _custodyModel = MutableStateFlow<CustodyModel?>(null)
    val custodyModel: StateFlow<CustodyModel?> = _custodyModel.asStateFlow()

    /**
     * Next custody handover, or null when no model is configured or custody never switches.
     * Feeds the trailing half of the custody ribbon ("→ Mom in 2 days"). Shares
     * [HandoverCalculator] with the home dashboard so the two cannot disagree on the date.
     */
    val nextHandover: StateFlow<HandoverInfo?> = _custodyModel
        .map { model -> model?.let { HandoverCalculator.nextHandoverFrom(it, LocalDate.now()) } }
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
     * Gets custody for a specific date.
     * Uses CustodyModel if available, falls back to legacy schedules.
     *
     * @param date The date to check
     * @return "mom", "dad", or null
     */
    fun getCustodyForDate(date: LocalDate): String? {
        _custodyModel.value?.let { model ->
            return model.getCustodyFor(date)
        }

        val schedules = custodySchedules.value
        return CustodyHelper.getCustodyForDate(date, schedules)
    }

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
        private const val SEPARATOR =
            com.coparently.app.data.local.preferences.PreferenceKeys.LIST_SEPARATOR
    }
}
