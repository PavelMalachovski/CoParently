package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel

/**
 * The four things that can happen to a custody proposal, as pure functions over [SharedCustody] —
 * kept out of the repository so the table can be tested without Firestore, Room or a coroutine,
 * the same way [CustodyChangeAnnouncement] keeps the banner's decision testable.
 *
 * Every function returns a [Result] rather than throwing or silently no-oping. A refused
 * transition is a real outcome the UI has to show ("your co-parent already has a proposal
 * waiting"), and a silent no-op would be the invisible change this whole feature exists to
 * remove.
 *
 * **A proposal write leaves the pattern, [SharedCustody.lastModifiedBy] and
 * [SharedCustody.lastModifiedAtMillis] exactly as they were.** That is not tidiness: `lastModifiedBy`
 * is what [CustodyChangeAnnouncement] compares against the reader's own uid to ignore the echo
 * of its own write, so a proposal that stamped the proposer would make the co-parent's
 * not-yet-dismissed pattern change read as the proposer's own echo and swallow its banner.
 */
object CustodyProposalTransition {

    /**
     * Puts [model] to the co-parent.
     *
     * Refused when the *other* parent already has a proposal pending: overwriting it would erase
     * their request without either of them seeing it, which is the failure this feature exists to
     * prevent, reintroduced one layer up. Replacing one's own pending proposal is allowed — that
     * is a correction, not an overrule.
     *
     * @param current The shared document as it stands.
     * @param model The pattern being proposed.
     * @param repeatYearly Travels with the pattern; see [CustodyProposal.repeatYearly].
     * @param byUid The proposer — this device's own uid.
     * @param atIso ISO date-time of the proposal.
     */
    fun propose(
        current: SharedCustody,
        model: CustodyModel,
        repeatYearly: Boolean,
        byUid: String,
        atIso: String
    ): Result<SharedCustody> {
        val pending = current.proposal
        if (pending != null && pending.proposedBy != byUid) {
            return Result.failure(
                IllegalStateException("The co-parent already has a proposal waiting")
            )
        }
        return Result.success(
            current.copy(
                proposal = CustodyProposal(
                    model = model,
                    repeatYearly = repeatYearly,
                    proposedBy = byUid,
                    proposedAt = atIso
                )
            )
        )
    }

    /**
     * Takes back one's own pending proposal.
     *
     * Leaves no [SharedCustody.lastDecision] behind: nobody answered it, and recording a decision
     * would tell the withdrawing parent's co-parent that they declined something they never saw.
     */
    fun withdraw(current: SharedCustody, byUid: String): Result<SharedCustody> =
        current.pendingFrom(byUid).map { current.copy(proposal = null) }

    /**
     * Takes up the co-parent's proposal: it becomes the agreed pattern, stamped with the accepter
     * as its author.
     *
     * [SharedCustody.createdAt] is preserved, so agreeing a change does not re-date the
     * arrangement itself — the same rule `CustodyModelRepository` already follows when updating
     * the pattern directly.
     *
     * Two stamps, from one moment: [atMillis] dates the *document*, which is what orders two
     * phones' writes, while [atIso] records when the decision was made for the proposer to read
     * back. They are different questions and only the first has to survive a change of zone.
     */
    fun accept(
        current: SharedCustody,
        byUid: String,
        atIso: String,
        atMillis: Long
    ): Result<SharedCustody> =
        current.pendingForDecisionBy(byUid).map { pending ->
            current.copy(
                model = pending.model,
                repeatYearly = pending.repeatYearly,
                lastModifiedBy = byUid,
                lastModifiedAtMillis = atMillis,
                proposal = null,
                lastDecision = CustodyDecision(
                    outcome = CustodyDecisionOutcome.ACCEPTED,
                    by = byUid,
                    at = atIso,
                    proposalAt = pending.proposedAt,
                    note = null
                )
            )
        }

    /**
     * Turns the co-parent's proposal down. The agreed pattern and its authorship are untouched —
     * a refusal changes nothing except that the proposer now knows.
     *
     * @param note Optional reason. Blank is stored as null rather than as an empty string, so the
     *   UI has one thing to test for.
     */
    fun decline(
        current: SharedCustody,
        byUid: String,
        atIso: String,
        note: String?
    ): Result<SharedCustody> = current.pendingForDecisionBy(byUid).map { pending ->
        current.copy(
            proposal = null,
            lastDecision = CustodyDecision(
                outcome = CustodyDecisionOutcome.DECLINED,
                by = byUid,
                at = atIso,
                proposalAt = pending.proposedAt,
                note = note?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** The pending proposal, if [uid] is the one who made it. */
    private fun SharedCustody.pendingFrom(uid: String): Result<CustodyProposal> {
        val pending = proposal
            ?: return Result.failure(IllegalStateException("No proposal is pending"))
        return if (pending.proposedBy == uid) {
            Result.success(pending)
        } else {
            Result.failure(IllegalStateException("Only the proposer may withdraw a proposal"))
        }
    }

    /**
     * The pending proposal, if [uid] is entitled to decide it — which the proposer never is.
     *
     * A parent accepting their own proposal is precisely the unilateral change this feature
     * replaces, arriving through the new door instead of the old one.
     */
    private fun SharedCustody.pendingForDecisionBy(uid: String): Result<CustodyProposal> {
        val pending = proposal
            ?: return Result.failure(IllegalStateException("No proposal is pending"))
        return if (pending.proposedBy == uid) {
            Result.failure(IllegalStateException("A parent may not decide their own proposal"))
        } else {
            Result.success(pending)
        }
    }
}
