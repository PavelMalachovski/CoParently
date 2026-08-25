package com.coparently.app.presentation.common

import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this family's app should offer child records, pet records, or both.
 *
 * One derivation, in one place, for the same reason [ParentsSource] exists: the answer decides
 * which rows Home and Settings draw, and two screens computing it by different routes is two
 * screens that eventually disagree about whether a family has a dog.
 *
 * **The union of the two parents' answers, and silence contributes nothing.** One parent saying
 * "children" must be enough for the child records to appear on both phones; a parent whose build
 * never wrote the field must not widen the other's real answer to everything. When *neither* has
 * answered — every account that predates the question — the union is empty and reads as
 * [FamilyKind.ALL], so an upgrade never hides a section somebody was already using.
 *
 * Deliberately **not** a gate on the navigation routes. `Screen.Pets` and `Screen.ChildInfo` stay
 * registered whatever this says: a deep link, or a push about a record the co-parent just
 * changed, must not land on a route that is not there.
 */
@Singleton
class FamilyKindSource @Inject constructor(
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository
) {

    /**
     * Scope the shared upstream runs in. Owned here rather than injected, exactly as
     * [ParentsSource] documents: this is a process-lifetime singleton and nothing outside it has
     * a reason to cancel the sharing.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The two raw answers, **shared once**, before either question is asked of them.
     *
     * `shareIn`, not a cold flow per caller, and the KDoc this replaces was wrong about why it
     * did not need one: [PairingRepository.observePairingState] attaches *three* Firestore
     * snapshot listeners per subscription and calls `loadPartner()` — a `users/{partnerId}` read
     * — inside its combine transform. It is not riding anything warm. Every ViewModel exposing
     * `caresFor` stood up its own copy, and splitting this class into two questions was about to
     * double that again.
     *
     * `replay = 1` so a screen opening later gets the last real answer rather than a synthetic
     * "nobody has answered"; `replayExpirationMillis = 0` so that cache is dropped once the last
     * subscriber leaves, and the first collector on the next signed-in account cannot be handed
     * the previous one's — the account-switch trap [ParentsSource] documents at length.
     */
    private val answers: Flow<Answers> by lazy {
        combine(
            userRepository.observeCurrentUserId(),
            userRepository.getAllUsers(),
            pairingRepository.observePairingState()
        ) { uid, users, pairing ->
            // Matched by uid, never "the only row": sign-out does not clear Room, so a device
            // where two accounts have signed in holds two rows and picking by position answers
            // for a stranger. The same rule `ParentsSource` documents at length.
            Answers(
                mine = users.firstOrNull { it.id == uid }?.caresFor.orEmpty(),
                theirs = (pairing as? PairingState.Paired)?.partner?.caresFor.orEmpty()
            )
        }
            .distinctUntilChanged()
            .shareIn(
                scope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
                replay = 1
            )
    }

    /** What to show: the union of both answers, empty reading as [FamilyKind.ALL]. */
    fun observe(): Flow<Set<FamilyKind>> = answers
        .map { FamilyKind.effective(it.mine, it.theirs) }
        .distinctUntilChanged()

    /**
     * **This parent's own answer**, which is the only one they can change.
     *
     * Separate from [observe] because the two are asked by different questions. "Which sections
     * does this app show" is the union, and rightly so — one parent saying "children" must be
     * enough for both phones. "Which boxes does the Settings dialog open with" is not: that
     * dialog writes to `users/{uid}.caresFor`, this parent's row alone, so seeding it with the
     * union made every checkbox a lie. Saving without touching anything copied the co-parent's
     * answer onto your record, and a box you unticked came straight back because they still held
     * it.
     *
     * A parent who has never answered is offered [FamilyKind.DEFAULT] — **not** the union, and
     * not the co-parent's answer. Seeding an unanswered parent from the union is the same
     * laundering wearing a different hat: it is exactly the population the report was about, and
     * `effective(∅, theirs)` collapses to `theirs`. A dialog whose Save writes this parent's row
     * must never put the other parent's words in their mouth.
     */
    fun observeMine(): Flow<Set<FamilyKind>> = answers
        .map { it.mine.ifEmpty { FamilyKind.DEFAULT } }
        .distinctUntilChanged()

    /**
     * The **co-parent's** answer alone, or empty when there is none.
     *
     * Exists so the Settings dialog can tell the truth about what unticking a kind will do: a
     * kind the co-parent still holds stays visible for both of you, and a control that silently
     * does nothing is what CLAUDE.md's design item 8 forbids. Read from the same shared upstream,
     * so asking costs no extra listener.
     */
    fun observeTheirs(): Flow<Set<FamilyKind>> = answers
        .map { it.theirs }
        .distinctUntilChanged()

    /**
     * The two answers side by side, kept apart so each question can be asked of the right one.
     *
     * @property mine This device's signed-in parent's answer; empty when never answered.
     * @property theirs The co-parent's; empty when unpaired, still loading, or unanswered.
     */
    private data class Answers(
        val mine: Set<FamilyKind>,
        val theirs: Set<FamilyKind>
    )

    private companion object {
        /** Keeps the shared upstream warm across a tab switch or a config change. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
