package com.coparently.app.presentation.custody

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
                CustodyModelType.TWO_TWO_THREE -> model.momDayIndices.contains(0)
                CustodyModelType.THREE_FOUR_FOUR_THREE -> model.momDayIndices.contains(0)
                CustodyModelType.CUSTOM -> true
            },
            customPatternDays = model.patternDays,
            customMomDays = model.momDayIndices
        )
    }

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

    /**
     * Saves the custody model configuration.
     */
    fun save(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        if (!state.isValid) return

        _uiState.value = state.copy(isLoading = true)

        viewModelScope.launch {
            try {
                when (state.selectedModelType) {
                    CustodyModelType.WEEK_ON_WEEK_OFF -> {
                        custodyModelRepository.createWeekOnWeekOff(
                            startDate = state.startDate,
                            momFirst = state.momFirst
                        )
                    }
                    CustodyModelType.TWO_TWO_THREE -> {
                        custodyModelRepository.createTwoTwoThree(
                            startDate = state.startDate,
                            momStartsFirst = state.momFirst
                        )
                    }
                    CustodyModelType.THREE_FOUR_FOUR_THREE -> {
                        custodyModelRepository.createThreeFourFourThree(
                            startDate = state.startDate,
                            momStartsFirst = state.momFirst
                        )
                    }
                    CustodyModelType.CUSTOM -> {
                        custodyModelRepository.createCustom(
                            startDate = state.startDate,
                            patternDays = state.customPatternDays,
                            momDayIndices = state.customMomDays
                        )
                    }
                }
                _uiState.value = state.copy(isLoading = false, isSaved = true)
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
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
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
