package com.coparently.app.presentation.pairing

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the pairing conflict screen: the two patterns to compare, who the two parents are,
 * and the one action the screen offers twice.
 *
 * The decision itself is not here — it is [CustodyConflictResolver], already made by the time
 * this screen opens. What is left is presenting it and writing the answer.
 */
@HiltViewModel
class CustodyConflictViewModel @Inject constructor(
    private val pendingCustodyConflict: PendingCustodyConflict,
    private val custodyModelRepository: CustodyModelRepository,
    parentsSource: ParentsSource
) : ViewModel() {

    /** The two patterns to choose between, or null when there is nothing outstanding. */
    val prompt: StateFlow<CustodyConflictPrompt?> = pendingCustodyConflict.prompt

    /** The two parents, for naming the colours in each pattern's legend. */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Parents())

    private val _isSaving = MutableStateFlow(false)

    /** True while a choice is being written; both actions are disabled meanwhile. */
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _resolved = MutableStateFlow(false)

    /** True once a choice has been written and the screen should close. */
    val resolved: StateFlow<Boolean> = _resolved.asStateFlow()

    /**
     * Makes [model] the pair's pattern: active in Room, and pushed to the shared document.
     *
     * The rejected pattern is deactivated rather than deleted — `saveAndActivate` deactivates
     * every model before inserting this one — so it stays in `getAllModels()` and nobody's
     * schedule disappears because of a choice made in one moment.
     *
     * A write failure still closes the screen. There is no Back here by design, so a failure
     * that kept the screen open would trap the user on it with two buttons that do not work;
     * Room keeps whatever it held, the shared document keeps whatever it held, and the mirror
     * reconciles the two by its usual last-write-wins rule.
     */
    fun choose(model: CustodyModel) {
        if (_isSaving.value || _resolved.value) return
        _isSaving.value = true
        viewModelScope.launch {
            runCatching { custodyModelRepository.saveAndActivate(model) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to save the chosen custody pattern", e)
                }
            _isSaving.value = false
            _resolved.value = true
        }
    }

    /**
     * Drops the pending conflict when the screen is popped — after a choice, or when the screen
     * is left for any other reason. Not in [choose]: clearing there would null the two patterns
     * the screen is still rendering for the frame before it closes.
     */
    override fun onCleared() {
        pendingCustodyConflict.clear()
    }

    private companion object {
        const val TAG = "CustodyConflictVM"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
