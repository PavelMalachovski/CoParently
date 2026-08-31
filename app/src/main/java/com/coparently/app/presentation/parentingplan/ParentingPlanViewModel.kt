package com.coparently.app.presentation.parentingplan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.repository.ParentingPlanPair
import com.coparently.app.data.repository.ParentingPlanRepository
import com.coparently.app.domain.family.FamilyKey
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.parentingplan.ParentingPlanEntry
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the parenting-plan screen is showing. */
sealed interface ParentingPlanUiState {

    /** Before the pairing state has answered. */
    data object Loading : ParentingPlanUiState

    /**
     * No co-parent, so there is nothing to compare against.
     *
     * A plan is two halves and a diff. Letting one parent fill it in alone would mean storing it
     * under an id that is not a family's, and then moving every answer at pairing — a migration
     * with nothing to catch it going wrong, in exchange for a screen that cannot yet do the one
     * thing it is for.
     */
    data object NoCoParent : ParentingPlanUiState

    /** Both halves, whichever of them exist. */
    data class Ready(val plan: ParentingPlanPair, val coParentUid: String) : ParentingPlanUiState
}

/**
 * The parenting plan: this parent's answers, the co-parent's, and where they agree (MON-5).
 */
@HiltViewModel
class ParentingPlanViewModel @Inject constructor(
    private val repository: ParentingPlanRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    parentsSource: ParentsSource,
    private val crashlyticsManager: CrashlyticsManager
) : ViewModel() {

    /** Names and colours for the two parents; the screen renders both halves side by side. */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), Parents())

    /**
     * The family whose plan this is, or null while there is nobody to plan with.
     *
     * Built from the pairing state rather than from [Parents.coParent], which is null for a pair
     * that predates slot assignment — gating on it would hide the feature from exactly the
     * long-standing pairs most likely to want it.
     */
    private val scope: Flow<PlanScope?> = combine(
        userRepository.observeCurrentUserId(),
        pairingRepository.observePairingState()
    ) { uid, pairing -> scopeFor(uid, pairing) }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ParentingPlanUiState> = scope
        .flatMapLatest { planScope ->
            if (planScope == null) {
                flowOf(ParentingPlanUiState.NoCoParent)
            } else {
                repository.observe(planScope.familyId, planScope.myUid)
                    .map { ParentingPlanUiState.Ready(it, planScope.partnerUid) }
            }
        }
        .catch { e ->
            report("observe parenting plan", e)
            emit(ParentingPlanUiState.NoCoParent)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            ParentingPlanUiState.Loading
        )

    /**
     * Records this parent's answer to [questionId].
     *
     * Reads the family from the flow's *current* value rather than from a `WhileSubscribed`
     * stream that may never have been collected — this is a save path, and the screen that calls
     * it is by definition collecting [uiState], so the answer it needs is already on screen.
     */
    fun answer(questionId: String, answer: String) = edit { entry, now ->
        entry.withAnswer(questionId, answer, now)
    }

    /**
     * Ticks or unticks agreement with the co-parent's current wording for [questionId].
     *
     * @param theirAnswer What the co-parent's answer says right now, or null to untick. An
     *   agreement is to a wording, not to a question — see [ParentingPlanEntry.agreedTo].
     */
    fun agree(questionId: String, theirAnswer: String?) = edit { entry, now ->
        entry.withAgreement(questionId, theirAnswer, now)
    }

    private fun edit(change: (ParentingPlanEntry, Long) -> ParentingPlanEntry) {
        viewModelScope.launch {
            try {
                val planScope = scope.first() ?: return@launch
                val current = (uiState.value as? ParentingPlanUiState.Ready)?.plan?.yours
                    ?: ParentingPlanEntry()
                repository.save(
                    familyId = planScope.familyId,
                    myUid = planScope.myUid,
                    entry = change(current, System.currentTimeMillis())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                report("save parenting plan answer", e)
            }
        }
    }

    private fun report(operation: String, cause: Throwable) {
        Log.w(TAG, "Could not $operation", cause)
        crashlyticsManager.recordException(cause)
    }

    /** The family a plan belongs to, and which half of it is this parent's. */
    private data class PlanScope(val familyId: String, val myUid: String, val partnerUid: String)

    private fun scopeFor(uid: String?, pairing: PairingState): PlanScope? {
        val myUid = uid?.takeIf { it.isNotBlank() } ?: return null
        val partnerUid = (pairing as? PairingState.Paired)?.partner?.id?.takeIf { it.isNotBlank() }
            ?: return null
        val familyId = FamilyKey.orNull(myUid, partnerUid) ?: return null
        return PlanScope(familyId = familyId, myUid = myUid, partnerUid = partnerUid)
    }

    companion object {
        private const val TAG = "ParentingPlanVM"

        /** Matches the other shared sources, so a rotation does not tear the listener down. */
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
