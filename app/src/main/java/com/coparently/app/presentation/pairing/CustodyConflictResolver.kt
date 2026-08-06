package com.coparently.app.presentation.pairing

import com.coparently.app.domain.model.CustodyModel

/**
 * What the two custody patterns known at the moment of pairing add up to.
 *
 * Three outcomes, of which only one asks the user anything. The distinction that matters is
 * between [Conflict] — which must be put to the accepter, because no clock and no client may
 * decide whose schedule survives — and everything else, which is a fact rather than a question.
 */
sealed interface CustodyConflict {

    /**
     * Nothing to ask: at most one pattern exists, or the two describe the same arrangement.
     *
     * @property model The pattern that stands, or null when neither parent has one at all.
     *   When both exist and agree it is **theirs**, not this device's copy: the shared document
     *   is what both phones converge on, so the local copy is the one that has to move.
     */
    data class NoConflict(val model: CustodyModel?) : CustodyConflict

    /**
     * This device has no pattern of its own, so the co-parent's is simply adopted.
     *
     * Kept distinct from [NoConflict] carrying the same model because the two are different
     * facts — "there was nothing here" versus "the two agreed" — and a log that cannot tell
     * them apart cannot explain, after the fact, why a schedule appeared on a phone.
     */
    data class NoLocal(val theirs: CustodyModel) : CustodyConflict

    /**
     * Both parents have a pattern and the two disagree. The accepter chooses; nothing is
     * written until they do.
     *
     * @property mine This device's pattern, **already complemented** for the slot pairing just
     *   moved it to. An un-complemented model here is the defect this whole screen exists to
     *   prevent — see [CustodyConflictResolver].
     * @property theirs The pair's shared pattern as it stood at the moment of accept.
     */
    data class Conflict(val mine: CustodyModel, val theirs: CustodyModel) : CustodyConflict

    /**
     * The pattern this device should end up on when no question needs asking, or null when
     * there is nothing to write — either because nobody has a pattern, or because the answer
     * is the user's to give.
     */
    val settled: CustodyModel?
        get() = when (this) {
            is NoConflict -> model
            is NoLocal -> theirs
            is Conflict -> null
        }
}

/**
 * Decides whether the two patterns known at pairing time need the accepter's attention.
 *
 * Pure and synchronous, so the decision is testable without a composition, a `StateFlow` or a
 * Firestore stub — the screen above it keeps no logic beyond wiring.
 *
 * **The complement is the caller's job, and it must happen first.** `momDayIndices` means "the
 * days slot 1 has custody"; accepting an invitation moves the accepter from slot 1 to slot 2, so
 * their stored pattern silently starts describing the *co-parent's* days. Passing it here
 * un-complemented turns two identical arrangements into a [Conflict] and offers the accepter
 * their own schedule inverted — they reject it, believing they are rejecting a stranger's
 * pattern, and hand over exactly the days they meant to keep.
 *
 * The complement is not done here because this function is also the right answer for a caller
 * whose slot did *not* change, and a resolver that silently complemented would be wrong for
 * exactly that caller. `CustodyConflictResolverTest` pins both directions.
 */
object CustodyConflictResolver {

    /**
     * @param mineAfterFlip This device's active pattern, complemented if pairing moved it to
     *   the other slot, or null when this device has none.
     * @param theirs The pair's shared pattern, or null when there is none.
     */
    fun resolve(mineAfterFlip: CustodyModel?, theirs: CustodyModel?): CustodyConflict {
        if (mineAfterFlip == null) {
            return if (theirs == null) {
                CustodyConflict.NoConflict(null)
            } else {
                CustodyConflict.NoLocal(theirs)
            }
        }
        if (theirs == null) return CustodyConflict.NoConflict(mineAfterFlip)
        // By outcome, not by field: start dates a whole cycle apart, and two different
        // modelTypes that happen to assign the same days, are agreement, not disagreement.
        return if (mineAfterFlip.isEquivalentTo(theirs)) {
            CustodyConflict.NoConflict(theirs)
        } else {
            CustodyConflict.Conflict(mine = mineAfterFlip, theirs = theirs)
        }
    }
}
