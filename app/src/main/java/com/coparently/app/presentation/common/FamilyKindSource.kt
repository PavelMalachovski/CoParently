package com.coparently.app.presentation.common

import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    fun observe(): Flow<Set<FamilyKind>> = combine(
        userRepository.observeCurrentUserId(),
        userRepository.getAllUsers(),
        pairingRepository.observePairingState()
    ) { uid, users, pairing ->
        // Matched by uid, never "the only row": sign-out does not clear Room, so a device where
        // two accounts have signed in holds two rows and picking by position answers for a
        // stranger. The same rule `ParentsSource` documents at length.
        val mine = users.firstOrNull { it.id == uid }?.caresFor.orEmpty()
        val theirs = (pairing as? PairingState.Paired)?.partner?.caresFor.orEmpty()
        FamilyKind.effective(mine, theirs)
    }.distinctUntilChanged()
}
