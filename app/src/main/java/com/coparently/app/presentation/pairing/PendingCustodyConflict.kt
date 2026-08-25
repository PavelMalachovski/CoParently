package com.coparently.app.presentation.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A conflict, plus the one fact the screen needs that the conflict itself does not carry.
 *
 * @property conflict The two disagreeing patterns.
 * @property mySlot The slot pairing has just assigned to this device, straight from the
 *   `acceptPairingInvitation` response.
 *
 *   Taken from there rather than from `Parents.me.slot`, and that is not belt-and-braces:
 *   `UserRepositoryImpl` only stamps a role when it *creates* a Room row and preserves it on
 *   every later write, so an accepter's local row still reads the slot they held before pairing
 *   until the periodic `SyncWorker` pass catches up — up to fifteen minutes, and this screen
 *   opens seconds after the accept. Colouring the two preview grids from that stale slot would
 *   put the accepter's name against the co-parent's colour on the one screen whose entire job is
 *   "which of these days are mine". Naming, separately, goes by uid — the ruling this branch
 *   already made for the custody-change banner.
 */
data class CustodyConflictPrompt(
    val conflict: CustodyConflict.Conflict,
    val mySlot: String
)

/**
 * The conflict awaiting the accepter's decision, handed from `PairingViewModel` to
 * `CustodyConflictViewModel`.
 *
 * It is held **by value**, captured at the moment pairing was accepted, and that is the point.
 * Re-deriving the two patterns on the conflict screen would race the shared-custody mirror: the
 * moment the pairing lands, `CustodyModelRepository` starts folding the co-parent's document into
 * Room and, if this device's pre-pairing pattern carries the newer `lastModifiedAt`, re-pushes
 * the local one over it. Either half of "mine vs. theirs" could therefore have changed under the
 * screen before the user has read it. A snapshot cannot: whichever pattern the user picks is
 * written last, and last write wins.
 *
 * A route argument cannot carry two custody patterns, and a nav-graph-scoped ViewModel cannot
 * either — the Pairing entry it would hang off may be popped. Hence a singleton, deliberately
 * small: set once, read once, cleared when the conflict screen goes away.
 *
 * Not persisted across process death. A conflict that outlives the process is lost, and the
 * screen leaves rather than showing an empty comparison — the local pattern stays active and the
 * shared document is untouched, so nothing is destroyed by the loss; the two simply stay
 * unreconciled until someone edits the schedule.
 */
@Singleton
class PendingCustodyConflict @Inject constructor() {

    private val _prompt = MutableStateFlow<CustodyConflictPrompt?>(null)

    /** The conflict to put to the user, or null when there is none outstanding. */
    val prompt: StateFlow<CustodyConflictPrompt?> = _prompt.asStateFlow()

    /** Records a conflict for the screen that is about to open. */
    fun set(prompt: CustodyConflictPrompt) {
        _prompt.value = prompt
    }

    /** Drops the conflict once it has been decided, or once the screen holding it is gone. */
    fun clear() {
        _prompt.value = null
    }
}
