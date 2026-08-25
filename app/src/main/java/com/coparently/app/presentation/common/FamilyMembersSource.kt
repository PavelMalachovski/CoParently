package com.coparently.app.presentation.common

import com.coparently.app.domain.family.FamilyMemberRef
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.PetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Somebody this family cares for, ready to be named on a chip.
 *
 * @property ref What a record stores to point at them.
 * @property name What a parent calls them.
 */
data class FamilyMember(
    val ref: FamilyMemberRef,
    val name: String
)

/**
 * The children and the pets, in one list, for every screen that has to ask "who is this about".
 *
 * Children first, then pets, each in the order their own list holds — so the chips do not
 * reshuffle when an unrelated record syncs.
 *
 * **How many there are is derived here and nowhere else.** No count is stored, so this list is
 * the single answer to "does this family have more than one of anybody", which is the question
 * every per-member affordance is gated on — see the rule in CLAUDE.md, and
 * [FamilyMemberChips], which enforces it rather than trusting each call site to remember.
 *
 * A stream, because it is what screens *render*. A save path must not read its `.value` — the
 * defect CLAUDE.md records for `ExpenseViewModel.sharedWith` — and does not need to: what a
 * parent picked comes down from the form as an explicit list.
 */
@Singleton
class FamilyMembersSource @Inject constructor(
    private val childInfoRepository: ChildInfoRepository,
    private val petRepository: PetRepository
) {

    /**
     * Re-emits whenever a child or a pet is added, renamed or removed.
     *
     * A cold flow, deliberately — no `shareIn` and no scope of its own, which is where
     * [ParentsSource] differs and why. That one joins three Firestore *pairing listeners*, so
     * sharing one subscription across screens is worth a singleton scope. This one combines two
     * Room queries, which the invalidation tracker already multiplexes; a second collector costs
     * an observer, not a listener.
     *
     * The scope also made this untestable in the way that matters. A `shareIn` on
     * `Dispatchers.Default` runs outside the test scheduler, so `advanceUntilIdle()` cannot make
     * it emit — a filter test would pass or fail on a race with a real thread. Collected on the
     * caller's `viewModelScope`, which is the test dispatcher under `Dispatchers.setMain`, it is
     * deterministic.
     */
    fun observe(): Flow<List<FamilyMember>> = combine(
        childInfoRepository.getAllChildInfo(),
        petRepository.getAllPets()
    ) { children, pets ->
        children.map { FamilyMember(FamilyMemberRef.Child(it.id), it.childName) } +
            pets.map { FamilyMember(FamilyMemberRef.Pet(it.id), it.name) }
    }
        // Room re-emits a whole table when any column of any row moves, and a medical note or a
        // vaccination date renames nobody.
        .distinctUntilChanged()
}
