package com.coparently.app.domain.custody

/** Whether a proposal was taken up or turned down. */
enum class CustodyDecisionOutcome { ACCEPTED, DECLINED }

/**
 * The most recent answer to a proposal, kept so the proposer learns the outcome.
 *
 * Only the latest is kept: a history of past proposals is out of scope, and the banner this feeds
 * is dismissed against [at] the same way the "schedule changed" banner is dismissed against
 * `SharedCustody.lastModifiedAtMillis`.
 *
 * @property outcome Accepted or declined.
 * @property by Firebase UID of whoever decided — never the proposer, which
 *   [CustodyProposalTransition] enforces.
 * @property at ISO date-time string of the decision.
 * @property proposalAt The [CustodyProposal.proposedAt] this answers, so a decision cannot be
 *   mistaken for the answer to a later proposal.
 * @property note Optional free text, offered on decline so a refusal can say why.
 */
data class CustodyDecision(
    val outcome: CustodyDecisionOutcome,
    val by: String,
    val at: String,
    val proposalAt: String,
    val note: String?
)
