package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel

/**
 * The pair's shared custody document: the pattern, plus what only the shared copy knows.
 *
 * [CustodyModel] is the pattern and deliberately stays that — it is what the calendar asks
 * "who has the child on this date". Who last changed it and when are facts about the
 * *document*, not about the schedule, and they exist only because two people write to it.
 *
 * @property model The custody pattern itself, carrying the id its writer gave it.
 * @property lastModifiedBy Firebase UID of whoever wrote the document last. Compared against
 *   the signed-in uid to tell "the co-parent changed the schedule" from this device's own echo.
 * @property lastModifiedAt ISO date-time string of that write, as everywhere else in this
 *   Firestore schema — dates cross the wire as strings, not as Firestore timestamps.
 * @property createdAt ISO date-time string of when the pair's arrangement was first written.
 *   Preserved across updates, so editing the pattern does not re-date the arrangement.
 * @property repeatYearly Mirrors `CustodyModelEntity.repeatYearly`. Always true for MVP; it
 *   lives on the entity rather than on [CustodyModel], which is why it travels here.
 * @property proposal A pattern awaiting the co-parent's answer, or null when nothing is pending.
 *   Deliberately orthogonal to [lastModifiedBy] and [lastModifiedAt]: a proposal write touches
 *   neither, so proposing right after the co-parent changed the pattern cannot make their
 *   not-yet-dismissed change read as this device's own echo and swallow its banner. See
 *   [CustodyProposalTransition].
 * @property lastDecision The most recent answer to a proposal, or null until the first one. Kept
 *   so the proposer learns the outcome; only the latest is retained.
 */
data class SharedCustody(
    val model: CustodyModel,
    val lastModifiedBy: String,
    val lastModifiedAt: String,
    val createdAt: String,
    val repeatYearly: Boolean = true,
    val proposal: CustodyProposal? = null,
    val lastDecision: CustodyDecision? = null
)
