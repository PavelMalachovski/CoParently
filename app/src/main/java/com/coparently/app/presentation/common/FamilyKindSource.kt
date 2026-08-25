package com.coparently.app.presentation.common

import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
     * What to show, as a stream.
     *
     * Cold and cheap on its own — it adds no Firestore listener of its own, riding the pairing
     * state that `ParentsSource` already keeps warm — so a ViewModel may collect it directly.
     */
    fun observe(): Flow<Set<FamilyKind>> = answers()
        .map { (mine, theirs) -> FamilyKind.effective(mine, theirs) }
        .distinctUntilChanged()

    /**
     * **This parent's own answer**, which is the only one they can change.
     *
     * Separate from [observe] because the two are asked by different questions. "Which sections
     * does this app show" is the union, and rightly so — one parent saying "children" must be
     * enough for both phones. "Which boxes does the Settings dialog open with" is not: that
     * dialog writes to `users/{uid}.caresFor`, this parent's row alone, so seeding it with the
     * union made every checkbox a lie. Saving without touching anything copied the co-parent's
     * answer into yours, and a box you unticked came straight back because they still held it.
     *
     * A parent who has never answered is offered the union rather than an empty dialog: it is
     * what the app is already showing them, so confirming it is a real answer rather than a
     * reset.
     */
    fun observeMine(): Flow<Set<FamilyKind>> = answers()
        .map { (mine, theirs) -> mine.ifEmpty { FamilyKind.effective(mine, theirs) } }
        .distinctUntilChanged()

    /** The two raw answers, before either question is asked of them. */
    private fun answers(): Flow<Pair<Set<FamilyKind>, Set<FamilyKind>>> = combine(
        userRepository.observeCurrentUserId(),
        userRepository.getAllUsers(),
        pairingRepository.observePairingState()
    ) { uid, users, pairing ->
        // Matched by uid, never "the only row": sign-out does not clear Room, so a device where
        // two accounts have signed in holds two rows and picking by position answers for a
        // stranger. The same rule `ParentsSource` documents at length.
        val mine = users.firstOrNull { it.id == uid }?.caresFor.orEmpty()
        val theirs = (pairing as? PairingState.Paired)?.partner?.caresFor.orEmpty()
        mine to theirs
    }
}
