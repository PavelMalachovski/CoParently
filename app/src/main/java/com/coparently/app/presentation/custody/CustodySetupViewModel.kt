package com.coparently.app.presentation.custody

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.data.repository.PatternSubmission
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.domain.model.MidweekContact
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

/** Keeps the parents flow warm across brief unsubscriptions (config changes). */
private const val PARENTS_STOP_TIMEOUT_MS = 5_000L

/**
 * ViewModel for custody setup screen.
 * Handles custody model selection and configuration.
 */
@HiltViewModel
class CustodySetupViewModel @Inject constructor(
    private val custodyModelRepository: CustodyModelRepository,
    parentsSource: ParentsSource
) : ViewModel() {

    /**
     * Signed-in parent and paired co-parent, for resolving a slot to a name — the "starts
     * first" toggle, the week quick-select buttons and the colour-dot legend all name a person.
     */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PARENTS_STOP_TIMEOUT_MS), Parents())

    private val _uiState = MutableStateFlow(CustodySetupUiState())
    val uiState: StateFlow<CustodySetupUiState> = _uiState.asStateFlow()

    private val _currentModel = MutableStateFlow<CustodyModel?>(null)
    val currentModel: StateFlow<CustodyModel?> = _currentModel.asStateFlow()

    init {
        loadCurrentModel()
    }

    /**
     * Loads the currently active custody model.
     */
    private fun loadCurrentModel() {
        viewModelScope.launch {
            custodyModelRepository.getActiveModel().collect { model ->
                _currentModel.value = model
                model?.let { updateUiFromModel(it) }
            }
        }
    }

    /**
     * Updates UI state from an existing model.
     */
    private fun updateUiFromModel(model: CustodyModel) {
        _uiState.value = _uiState.value.copy(
            selectedModelType = model.modelType,
            startDate = model.startDate,
            momFirst = when (model.modelType) {
                CustodyModelType.WEEK_ON_WEEK_OFF -> model.momDayIndices.contains(0)
                // By majority, not by day 0. This is the one preset that is not a 50/50 split:
                // the resident holds ten to twelve days of the fortnight and the contact parent
                // two to four, so the larger set is the resident's whatever those days are.
                // "Does slot 1 hold day 0" was true only while the contact set was the two
                // weekend days — a midweek contact on a **Monday**, every week, puts index 0
                // with the contact parent and flipped which parent the form reopened as
                // resident, silently offering to save the schedule the other way round.
                CustodyModelType.EVERY_OTHER_WEEKEND -> model.isResidentSlotOne()
                CustodyModelType.TWO_TWO_THREE -> model.momDayIndices.contains(0)
                CustodyModelType.THREE_FOUR_FOUR_THREE -> model.momDayIndices.contains(0)
                CustodyModelType.CUSTOM -> true
            },
            customPatternDays = model.patternDays,
            customMomDays = model.momDayIndices
        ).let { state ->
            if (model.modelType == CustodyModelType.EVERY_OTHER_WEEKEND) {
                state.withMidweekFrom(model)
            } else {
                state
            }
        }
    }

    /**
     * Reads a saved every-other-weekend model's midweek day back into the form.
     *
     * The model stores only which fortnight indices belong to slot 1, so the midweek day is
     * recovered rather than stored: take the contact parent's days, drop the two weekend
     * indices, and whatever weekday is left is the one that was chosen. Re-opening the screen
     * has to show the schedule the family actually has — a form that silently reset the toggle
     * would turn "save" into "remove the midweek day".
     */
    private fun CustodySetupUiState.withMidweekFrom(model: CustodyModel): CustodySetupUiState {
        val residentIsSlotOne = model.isResidentSlotOne()
        val contactDays = if (residentIsSlotOne) {
            (0 until model.patternDays).toSet() - model.momDayIndices
        } else {
            model.momDayIndices
        }
        val midweekIndices = contactDays - setOf(CONTACT_SATURDAY, CONTACT_SUNDAY)
        val day = midweekIndices.minOrNull()?.let { DayOfWeek.of((it % DAYS_IN_WEEK) + 1) }
            ?: return copy(midweekEnabled = false)
        return copy(
            midweekEnabled = true,
            midweekDay = day,
            midweekEveryWeek = midweekIndices.size > 1
        )
    }

    /**
     * Whether slot 1 is the parent the child lives with, for a resident/contact pattern.
     *
     * By share of the fortnight, which is the definition, rather than by any particular index:
     * every index the contact parent holds is negotiable — the two weekend days plus an optional
     * midweek day that may itself be a Monday — while "holds most of the cycle" is exactly what
     * makes a parent the resident one. Only meaningful for a pattern that is not a 50/50 split.
     */
    private fun CustodyModel.isResidentSlotOne(): Boolean =
        momDayIndices.size * 2 > patternDays

    /**
     * Selects a model type.
     */
    fun selectModelType(type: CustodyModelType) {
        _uiState.value = _uiState.value.copy(
            selectedModelType = type,
            // Reset custom settings when switching away from custom
            customPatternDays = if (type == CustodyModelType.CUSTOM) _uiState.value.customPatternDays else 14,
            customMomDays = if (type == CustodyModelType.CUSTOM) _uiState.value.customMomDays else emptySet()
        )
    }

    /**
     * Sets the start date for the pattern.
     */
    fun setStartDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(startDate = date)
    }

    /**
     * Sets whether mom starts first in the pattern.
     */
    fun setMomFirst(momFirst: Boolean) {
        _uiState.value = _uiState.value.copy(momFirst = momFirst)
    }

    /**
     * Sets the number of days in a custom pattern.
     */
    fun setCustomPatternDays(days: Int) {
        val validDays = days.coerceIn(7, 28) // Reasonable range
        _uiState.value = _uiState.value.copy(
            customPatternDays = validDays,
            // Clear mom days that are out of range
            customMomDays = _uiState.value.customMomDays.filter { it < validDays }.toSet()
        )
    }

    /**
     * Toggles a day in the custom pattern for mom.
     */
    fun toggleCustomMomDay(dayIndex: Int) {
        val currentDays = _uiState.value.customMomDays.toMutableSet()
        if (currentDays.contains(dayIndex)) {
            currentDays.remove(dayIndex)
        } else {
            currentDays.add(dayIndex)
        }
        _uiState.value = _uiState.value.copy(customMomDays = currentDays)
    }

    /** Turns the midweek contact day on or off. */
    fun setMidweekEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(midweekEnabled = enabled)
    }

    /**
     * Picks which weekday the midweek contact falls on.
     *
     * A weekend day is ignored rather than refused loudly: the picker only offers Monday to
     * Friday, so reaching here with one would be a programming error, and [MidweekContact]
     * would throw on construction inside the save.
     */
    fun setMidweekDay(day: DayOfWeek) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return
        _uiState.value = _uiState.value.copy(midweekDay = day)
    }

    /** Every week, or only the week that has no contact weekend. */
    fun setMidweekEveryWeek(everyWeek: Boolean) {
        _uiState.value = _uiState.value.copy(midweekEveryWeek = everyWeek)
    }

    /**
     * Saves the custody model configuration.
     */
    fun save(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        if (!state.isValid) return

        _uiState.value = state.copy(isLoading = true)

        viewModelScope.launch {
            try {
                val submission = when (state.selectedModelType) {
                    CustodyModelType.WEEK_ON_WEEK_OFF -> custodyModelRepository.createWeekOnWeekOff(
                        startDate = state.startDate,
                        momFirst = state.momFirst
                    )
                    CustodyModelType.EVERY_OTHER_WEEKEND -> custodyModelRepository.createEveryOtherWeekend(
                        startDate = state.startDate,
                        momIsResident = state.momFirst,
                        midweek = state.midweek
                    )
                    CustodyModelType.TWO_TWO_THREE -> custodyModelRepository.createTwoTwoThree(
                        startDate = state.startDate,
                        momStartsFirst = state.momFirst
                    )
                    CustodyModelType.THREE_FOUR_FOUR_THREE -> custodyModelRepository.createThreeFourFourThree(
                        startDate = state.startDate,
                        momStartsFirst = state.momFirst
                    )
                    CustodyModelType.CUSTOM -> custodyModelRepository.createCustom(
                        startDate = state.startDate,
                        patternDays = state.customPatternDays,
                        momDayIndices = state.customMomDays
                    )
                }
                _uiState.value = state.copy(
                    isLoading = false,
                    isSaved = true,
                    // Item 7: when paired with an agreed schedule the change is a proposal the
                    // co-parent must accept — the screen says so instead of implying it applied.
                    proposedForApproval = submission == PatternSubmission.PROPOSED
                )
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = state.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to save custody model"
                )
            }
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private companion object {
        /** Index of the contact Saturday in the fortnight: day 0 is Monday. */
        const val CONTACT_SATURDAY = 5

        /** Index of the contact Sunday. */
        const val CONTACT_SUNDAY = 6

        /** Days in a week, for turning a fortnight index back into a weekday. */
        const val DAYS_IN_WEEK = 7
    }
}

/**
 * UI state for custody setup screen.
 */
data class CustodySetupUiState(
    val selectedModelType: CustodyModelType = CustodyModelType.WEEK_ON_WEEK_OFF,
    val startDate: LocalDate = LocalDate.now(),
    val momFirst: Boolean = true,
    val customPatternDays: Int = 14,
    val customMomDays: Set<Int> = emptySet(),
    /**
     * Whether `výhradní péče se stykem` also gives the contact parent a midweek day.
     *
     * Off by default: a midweek day here is a **whole** day, overnight included, because the
     * model assigns a date to exactly one parent. Most orders say "afternoon". Defaulting it on
     * would hand over an overnight nobody agreed to.
     */
    val midweekEnabled: Boolean = false,
    /** Which weekday the midweek contact falls on. Monday to Friday. */
    val midweekDay: DayOfWeek = DayOfWeek.WEDNESDAY,
    /** True for both weeks of the fortnight; false for the week without the contact weekend. */
    val midweekEveryWeek: Boolean = true,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    /** True when the save was sent to the co-parent as a proposal rather than applied. */
    val proposedForApproval: Boolean = false,
    val error: String? = null
) {
    /**
     * Validates the current state.
     */
    val isValid: Boolean
        get() = when (selectedModelType) {
            CustodyModelType.CUSTOM -> customPatternDays > 0 && customMomDays.isNotEmpty()
            else -> true
        }

    /**
     * The midweek contact this state describes, or null when the pattern has none.
     *
     * Null for every model other than [CustodyModelType.EVERY_OTHER_WEEKEND] even when the flag
     * happens to be set: switching model type must not smuggle a midweek day into a pattern that
     * has no notion of one.
     */
    val midweek: MidweekContact?
        get() = if (selectedModelType == CustodyModelType.EVERY_OTHER_WEEKEND && midweekEnabled) {
            MidweekContact(midweekDay, midweekEveryWeek)
        } else {
            null
        }

    /**
     * The slot that starts the pattern, and the slot that follows it.
     *
     * Slots, not names and not text: this is a ViewModel, it has no `Context`, and the preview
     * sentence is a localized resource the screen formats with the parents' actual names. It
     * used to be a hardcoded English string here that said "Mom" and "Dad" outright, on the one
     * screen this branch rewrote to show names — which is exactly the sentence a reader would
     * have trusted least, sitting two rows under "Starts first: Olya".
     */
    val firstSlot: String get() = if (momFirst) "mom" else "dad"
    val secondSlot: String get() = if (momFirst) "dad" else "mom"
}
