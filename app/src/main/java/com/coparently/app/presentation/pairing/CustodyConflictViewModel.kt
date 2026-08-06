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

    private val _saveFailed = MutableStateFlow(false)

    /** Set when a choice could not be written; the screen surfaces it once and clears it. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /**
     * Makes [model] the pair's pattern: active in Room, and pushed to the shared document. This
     * is the one write in the whole flow that is a *decision*, so it is stamped now and must win
     * every later comparison — unlike the re-slot, which only re-expresses an arrangement.
     *
     * The rejected pattern is deactivated rather than deleted — `saveAndActivate` deactivates
     * every model before inserting this one — so it stays in `getAllModels()` and nobody's
     * schedule disappears because of a choice made in one moment. When the two patterns share an
     * id, deactivation is not enough: Room's insert REPLACEs on the primary key, so a copy is
     * archived first.
     *
     * **A failure keeps the screen open and says so.** Closing silently would tell a user who
     * chose "keep the schedule on this phone" that they had, while Room held something else.
     * Both actions come back enabled so the choice can be retried; the screen has no Back by
     * design, which is a reason to make the retry work, not a reason to leave without a word.
     */
    fun choose(model: CustodyModel) {
        val pending = pendingCustodyConflict.prompt.value ?: return
        if (_isSaving.value || _resolved.value) return
        _isSaving.value = true
        _saveFailed.value = false
        val rejected = with(pending.conflict) { if (model == mine) theirs else mine }
        viewModelScope.launch {
            runCatching {
                if (rejected.id == model.id) custodyModelRepository.archiveRejected(rejected)
                custodyModelRepository.saveAndActivate(model)
            }
                .onSuccess { _resolved.value = true }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to save the chosen custody pattern", e)
                    _saveFailed.value = true
                }
            _isSaving.value = false
        }
    }

    /** Marks the failure as shown; call once it has been presented. */
    fun consumeSaveFailure() {
        _saveFailed.value = false
    }

    /**
     * Drops the pending conflict when the screen is popped — after a choice, or when the screen
     * is left for any other reason. Not in [choose]: clearing there would null the two patterns
     * the screen is still rendering for the frame before it closes.
     */
    override fun onCleared() {
        super.onCleared()
        pendingCustodyConflict.clear()
    }

    private companion object {
        const val TAG = "CustodyConflictVM"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
