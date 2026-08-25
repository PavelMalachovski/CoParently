package com.coparently.app.domain.expenses

/** How a proposed split ratio ended. */
enum class SplitRatioOutcome {
    /** The co-parent agreed. The proposed ratio became the agreed one. */
    ACCEPTED,

    /** The co-parent turned it down. The agreed ratio did not move. */
    DECLINED
}

/**
 * A split ratio one parent has put to the other, still waiting for an answer.
 *
 * @property ratio What is being proposed.
 * @property proposedBy Firebase UID of the parent who proposed it. `firestore.rules` requires
 *   this to be the caller, and it is what stops either parent deciding their own proposal.
 * @property proposedAtMillis Epoch millis. **Not a naive `LocalDateTime`**, unlike
 *   `SharedCustody.lastModifiedAt`, whose zone-less ordering is a known defect (SEC-4) that
 *   decides which phone's document survives. A greenfield document costs nothing to get right.
 */
data class SplitRatioProposal(
    val ratio: SplitRatio,
    val proposedBy: String,
    val proposedAtMillis: Long
)

/**
 * The answer to a proposal, kept so the parent who proposed it learns the outcome.
 *
 * @property outcome Accepted or declined.
 * @property by Firebase UID of whoever answered.
 * @property atMillis Epoch millis of the answer.
 * @property proposalAtMillis The proposal this answers, so a stale decision cannot be mistaken
 *   for the answer to a newer one.
 */
data class SplitRatioDecision(
    val outcome: SplitRatioOutcome,
    val by: String,
    val atMillis: Long,
    val proposalAtMillis: Long
)

/**
 * The money agreement between the two parents: one document per pair.
 *
 * Modelled on `SharedCustody`, and for the same reason: an agreement needs the agreed value and
 * the pending proposal on the same document, so a diff needs no history and a reader cannot see
 * one without the other.
 *
 * Deliberately **not** stored on `custody_models`. That block's rules are shaped tightly around a
 * custody pattern, and its `lastModifiedAt` ordering defect is one CLAUDE.md says not to add
 * decisions to.
 *
 * @property ratio The agreed split. [SplitRatio.EVEN] until the family says otherwise.
 * @property participants The two uids, sorted, as the document id is derived from.
 * @property lastModifiedBy Firebase UID of whoever last moved the agreed ratio.
 * @property lastModifiedAtMillis Epoch millis of that write.
 * @property proposal A ratio waiting for the other parent's answer, or null.
 * @property lastDecision The most recent answer, or null before there has been one.
 */
data class FamilySettings(
    val ratio: SplitRatio,
    val participants: List<String>,
    val lastModifiedBy: String,
    val lastModifiedAtMillis: Long,
    val proposal: SplitRatioProposal? = null,
    val lastDecision: SplitRatioDecision? = null
)
