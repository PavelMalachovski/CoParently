package com.coparently.app.presentation.common

import android.util.Log
import com.coparently.app.domain.model.PairingState
import com.coparently.app.presentation.theme.ParentPalette
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two parents of this family, as this device knows them.
 *
 * Either side may be null and the two nulls mean different things: a null [me] can be an
 * account with no Room profile row at all — [UserRepositoryImpl][com.coparently.app.data.repository.UserRepositoryImpl]
 * never writes one for such an account, so [me] then stays null forever, not just until the
 * next emission — and a null [coParent] is nobody paired *or* a co-parent whose slot is not
 * known. Neither is ever filled in by inference — see [parentLabel]. Use [loaded] to tell "we
 * have a real answer, and it happens to be null" from "we have not asked yet".
 *
 * [isPaired] exists because [coParent] alone cannot answer "is there someone to choose between":
 * a legacy pair (paired before slot assignment shipped) has a real co-parent and a null
 * [coParent], since [PartnerSummary.role][com.coparently.app.domain.model.PartnerSummary.role]
 * is null until their document carries one. A screen that gates a two-person control on
 * `coParent != null` hides it from exactly that population — the pairs this app's slot
 * migration exists to serve — rather than from the family of one it was meant for.
 *
 * @property me The signed-in parent.
 * @property coParent The paired co-parent.
 * @property isPaired Whether this account is linked to a co-parent at all, independent of
 *   whether that co-parent's slot is known yet. Read the pairing state directly for this —
 *   never infer it from `coParent != null`.
 * @property loaded Whether this is a real answer rather than the synthetic starting value.
 *
 *   False only before [ParentsSource]'s upstream has emitted once. It is not "we know who both
 *   parents are": [me] can be null in a loaded answer forever, for an account with no Room
 *   profile row. A control that appears and then vanishes is worse than one that appears late,
 *   so anything that *hides* itself once the answer arrives waits on this.
 */
data class Parents(
    val me: NamedParent? = null,
    val coParent: NamedParent? = null,
    val isPaired: Boolean = false,
    val loaded: Boolean = false
) {
    /**
     * uid to slot, for whichever parents are known.
     *
     * The domain calls a slot a "role" (`Expense`/`User` predate the rename of the concept, and
     * the stored field name is part of the Firestore schema), so this is what
     * `calculateExpenseBalancesByCurrency` takes as its `roleByUid`.
     *
     * Note what this map does *not* do: it never contains a uid whose slot was assumed. A pair
     * whose two parents both still read `"mom"` produces two entries with the same value, which
     * is exactly what leaves `ExpenseBalance.splitKnown` false — the split bar stays hidden
     * because the two people genuinely cannot be told apart yet, not because the data is
     * missing.
     */
    val roleByUid: Map<String, String>
        get() = listOfNotNull(me, coParent).associate { it.uid to it.slot }

    /**
     * The two colours this family's calendar, chips and split bars should be drawn in.
     *
     * Derived here rather than at nineteen call sites: every one of them already holds a slot
     * — an event's `parentOwner`, a custody day, an expense's payer — so resolving
     * uid → choice once and handing down a slot-keyed palette keeps `ParentColors` a lookup.
     *
     * Falls back to pink and blue whenever a parent has not chosen, which is what the app
     * looked like before anyone could, and resolves the case where both picked the same —
     * see [ParentPalette.of], because two parents drawn identically defeats the whole point.
     */
    val palette: ParentPalette
        get() = ParentPalette.of(
            slot1Code = listOfNotNull(me, coParent).firstOrNull { it.slot != "dad" }?.colorCode,
            slot2Code = listOfNotNull(me, coParent).firstOrNull { it.slot == "dad" }?.colorCode
        )
}

/**
 * Who the two parents are, as one stream, for every ViewModel that has to name one.
 *
 * There is one of these rather than a copy of the derivation in each ViewModel, and that is the
 * whole point: the moment two screens work out who the co-parent is by different routes, one of
 * them starts labelling the calendar differently from the other and nothing in the type system
 * notices. Five ViewModels expose `parents` and all five get it from here.
 *
 * The two halves come from different places because the app stores them differently:
 *
 * - **[me]** is a real Room row, matched *by uid* against [UserRepository.observeCurrentUserId].
 *   Never "the only row": [UserRepository.deleteUser] is called from nowhere and sign-out does
 *   not clear Room, so a device where two accounts have signed in over time holds two rows and
 *   picking either by position would name the calendar after a stranger.
 * - **[coParent]** comes from the pairing listener's [PartnerSummary][com.coparently.app.domain.model.PartnerSummary],
 *   which reads their `users/{uid}` document directly. Room never stores a row for the other
 *   parent, which is why [UserRepository.getAllUsers] alone cannot answer this question.
 */
@Singleton
class ParentsSource @Inject constructor(
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository
) {

    /**
     * Scope the shared upstream runs in. Owned by this class rather than injected because the
     * object is a process-lifetime singleton and nothing outside it has any reason to cancel
     * the sharing; adding a qualifier and a module for one consumer would be more surface than
     * the problem deserves. `SupervisorJob` so a failure in one collector cannot cancel it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The shared upstream. **Every subscriber gets this one flow**, which is what makes the
     * `@Singleton` mean anything.
     *
     * Without sharing, each collector stood up its own copy — and this is not a cheap flow to
     * duplicate. [PairingRepository.observePairingState] attaches *three* Firestore snapshot
     * listeners per subscription and calls `loadPartner()` — a one-shot `users/{partnerId}`
     * read — inside its combine transform, so it re-fetches the partner document on every
     * emission of any of the three. With five ViewModels exposing `parents`, opening the
     * Calendar tab alone used to stand up two more full subscriptions on top of Home's.
     *
     * `WhileSubscribed` rather than `Eagerly`: when every screen has gone away there is no
     * reason to keep Firestore listeners attached. `replay = 1` so a screen opening later gets
     * the last real answer instead of a synthetic "nobody is known yet" — but only while the
     * upstream is still the *same account's* upstream. `replayExpirationMillis = 0` drops that
     * replay cache the instant the last subscriber leaves and [STOP_TIMEOUT_MS] elapses,
     * instead of the default `Long.MAX_VALUE`, which never drops it. Without this, the first
     * collector on the next signed-in account — right after a sign-out, before this singleton's
     * upstream has re-emitted anything for the new account — would be served the *previous*
     * account's [Parents] for a frame: their names, and their `roleByUid`, which feeds the
     * expense balance. Room is not cleared on sign-out either, so nothing else would catch it.
     *
     * The cost of that fix: a screen that re-subscribes more than [STOP_TIMEOUT_MS] after the
     * last one left (a cold re-entry into the Calendar tab, say) now starts from a synthetic
     * "nobody is known yet" every time, not only right after install. Callers that show UI while
     * [Parents.me] is null already have to handle it — a fresh process starts from exactly that
     * state — so this only widens how often that path is taken, not the set of states a caller
     * must handle.
     *
     * `by lazy` so that merely *constructing* this singleton does not reach for the pairing
     * repository. Nothing subscribes at construction either way — the underlying flows are cold
     * — but [signedInSlot] callers have no business touching the pairing side at all, and an
     * eager initialiser made that impossible to state or to test.
     */
    private val shared: Flow<Parents> by lazy {
        combine(
            userRepository.observeCurrentUserId(),
            userRepository.getAllUsers(),
            pairingRepository.observePairingState()
        ) { uid, users, pairing ->
            Parents(
                me = uid?.let { id -> users.firstOrNull { it.id == id } }?.asNamedParent(),
                coParent = (pairing as? PairingState.Paired)?.partner?.asNamedParent(),
                isPaired = pairing is PairingState.Paired,
                loaded = true
            )
        }
            // The pairing state re-emits whenever an invite list changes and Room re-emits the
            // whole row when an unrelated column moves; neither renames anybody.
            .distinctUntilChanged()
            .shareIn(
                scope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
                replay = 1
            )
    }

    /**
     * Re-emits on sign-in, sign-out, account switch, any local profile edit, and every pairing
     * transition — including the co-parent renaming themselves or being assigned a slot.
     */
    fun observe(): Flow<Parents> = shared

    /**
     * This device's own slot, or null when nobody is signed in, or when this account has no
     * Room profile row — which, like [Parents.me], can be forever, not just until the next
     * call: [UserRepositoryImpl][com.coparently.app.data.repository.UserRepositoryImpl] never
     * writes one for an account it cannot name.
     *
     * The cheap question, deliberately kept separate from [observe]: it needs the signed-in uid
     * and one Room row, and touches neither the pairing listener nor the co-parent's document.
     * Callers that only want to know *their own* slot — stamping a pickup confirmation, say —
     * must use this. Collecting [observe] for that answer stands up a whole pairing
     * subscription and a partner-document read to learn something Room already knows.
     */
    suspend fun signedInSlot(): String? {
        val uid = userRepository.getCurrentUserId() ?: return null
        return userRepository.getUserById(uid)?.role
    }

    /**
     * The co-parent's uid, or null when this account is unpaired.
     *
     * The other cheap question, and it exists for a sharper reason than [signedInSlot]: a
     * **save path** must never depend on somebody having subscribed to [observe]. Every
     * ViewModel shares that flow with `WhileSubscribed`, so `parents.value` answers `Parents()`
     * in any ViewModel instance no screen has collected — which is exactly what a form-only
     * route is. `ExpenseViewModel` read it that way to decide who a shared expense is split
     * between, and the Add Expense screen collects `agreedRatio` but not `parents`: every
     * expense was written naming only the payer, and the co-parent's phone showed a month in
     * which nobody owed anybody anything.
     *
     * Read from the signed-in parent's own Room row, which is where the pairing lives —
     * `FamilySettingsRepository.currentPair` derives the pair the same way. Never
     * [UserRepository.getAllUsers], which CLAUDE.md records cannot answer "who is the other
     * parent".
     */
    suspend fun coParentUid(): String? {
        val uid = userRepository.getCurrentUserId() ?: return null
        val partnerId = userRepository.getUserById(uid)?.partnerId
        if (partnerId.isNullOrBlank()) {
            // Not an error — an unpaired account has no co-parent — but it is also the answer a
            // paired account gets in the window between the accept callable returning and
            // `PairingRepositoryImpl` writing the pairing into Room. That window prices an
            // expense as unshared, and without this line the only evidence is a wrong balance.
            Log.i(TAG, "No co-parent on the local row for $uid; treating this account as unpaired")
            return null
        }
        return partnerId.takeIf { it != uid }
    }

    private companion object {
        const val TAG = "ParentsSource"

        /** Keeps the shared upstream warm across a tab switch or a config change. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
