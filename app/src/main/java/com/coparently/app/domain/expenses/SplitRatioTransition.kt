package com.coparently.app.domain.expenses

/**
 * The four things that can happen to a proposed split ratio, as pure functions.
 *
 * A copy of `CustodyProposalTransition`'s shape at a smaller scale, and matching it is the point:
 * both answer "may this parent do this?" without Firestore, Room or a coroutine, so the rules
 * that make a ratio an *agreement* rather than an announcement are testable on the JVM.
 *
 * Every function returns a [Result] rather than throwing or silently no-oping. A refused
 * transition is a real outcome the screen has to show, and a silent no-op is the invisible change
 * this whole family of features exists to remove.
 *
 * Two invariants are copied verbatim from custody, because they are what make this an agreement:
 * a parent may never decide **their own** proposal, and a proposal may never overwrite the
 * co-parent's pending one.
 */
object SplitRatioTransition {

    /**
     * Puts [ratio] to the co-parent.
     *
     * Refuses while the **co-parent's** proposal is pending: answering theirs is the way past it,
     * and letting a counter-proposal silently replace it would lose an answer they are waiting
     * for. Replacing one's *own* pending proposal is allowed — that is a correction, not an
     * overwrite of somebody else's word.
     *
     * Refuses a proposal equal to the agreed ratio: there is nothing to answer, and a card
     * asking the co-parent to confirm no change is noise in a feature whose whole value is that
     * it only speaks when something moved.
     *
     * @param current The pair's settings as they stand.
     * @param ratio The ratio being put forward.
     * @param byUid The parent proposing — this device's own uid.
     * @param atMillis Epoch millis of the proposal.
     */
    fun propose(
        current: FamilySettings,
        ratio: SplitRatio,
        byUid: String,
        atMillis: Long
    ): Result<FamilySettings> {
        val pending = current.proposal
        if (pending != null && pending.proposedBy != byUid) {
            return Result.failure(
                IllegalStateException("Answer your co-parent's proposal before making one")
            )
        }
        if (ratio == current.ratio) {
            return Result.failure(IllegalStateException("That is already the agreed split"))
        }
        return Result.success(
            current.copy(
                proposal = SplitRatioProposal(
                    ratio = ratio,
                    proposedBy = byUid,
                    proposedAtMillis = atMillis
                )
            )
        )
    }

    /** Takes a proposal back. Only the parent who made it may. */
    fun withdraw(current: FamilySettings, byUid: String): Result<FamilySettings> {
        val pending = current.proposal
            ?: return Result.failure(IllegalStateException("There is no proposal to withdraw"))
        if (pending.proposedBy != byUid) {
            return Result.failure(
                IllegalStateException("Only the parent who proposed it may withdraw it")
            )
        }
        return Result.success(current.copy(proposal = null))
    }

    /**
     * Agrees to the co-parent's proposal. The proposed ratio becomes the agreed one.
     *
     * **Only new expenses are priced by it.** Each expense carries the ratio it was recorded
     * under, so a renegotiated split cannot silently re-price a month both parents had already
     * settled — see `Expense.splitBasisPoints`.
     */
    fun accept(
        current: FamilySettings,
        byUid: String,
        atMillis: Long
    ): Result<FamilySettings> = decide(current, byUid, atMillis, SplitRatioOutcome.ACCEPTED)

    /** Turns the co-parent's proposal down. The agreed ratio does not move. */
    fun decline(
        current: FamilySettings,
        byUid: String,
        atMillis: Long
    ): Result<FamilySettings> = decide(current, byUid, atMillis, SplitRatioOutcome.DECLINED)

    private fun decide(
        current: FamilySettings,
        byUid: String,
        atMillis: Long,
        outcome: SplitRatioOutcome
    ): Result<FamilySettings> {
        val pending = current.proposal
            ?: return Result.failure(IllegalStateException("There is no proposal to answer"))
        if (pending.proposedBy == byUid) {
            return Result.failure(
                IllegalStateException("A parent may not decide their own proposal")
            )
        }
        val decision = SplitRatioDecision(
            outcome = outcome,
            by = byUid,
            atMillis = atMillis,
            proposalAtMillis = pending.proposedAtMillis
        )
        return Result.success(
            if (outcome == SplitRatioOutcome.ACCEPTED) {
                current.copy(
                    ratio = pending.ratio,
                    lastModifiedBy = byUid,
                    lastModifiedAtMillis = atMillis,
                    proposal = null,
                    lastDecision = decision
                )
            } else {
                current.copy(proposal = null, lastDecision = decision)
            }
        )
    }
}
