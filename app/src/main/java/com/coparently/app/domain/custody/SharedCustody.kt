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
 * @property lastModifiedAtMillis When that write happened, as epoch milliseconds. The field
 *   that decides: `CustodyModelRepository.isNewer` compares it and the mirror re-pushes the
 *   winner over the loser, so it must name one instant rather than a wall clock whose zone
 *   nothing records. See [CustodyTimestamps].
 * @property lastModifiedAt The same write as a naive ISO string, carried **only** for a
 *   co-parent still on a build that reads no other field. Never compared here, and never
 *   re-derived: it travels verbatim from whatever was read, because a swap write must leave it
 *   byte-identical — `swapWriteTouchesOnlyTheSwap` in `firestore.rules` denies a swap that
 *   changes it, and re-deriving it in a second parent's zone would produce a different string
 *   for the same instant.
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
 * @property dayOverrides One-off day swaps keyed by ISO date. Absent from the document reads as
 *   an empty map, never null: every document written before this field existed has no such key,
 *   and a null would make every caller test for two shapes of "none". See [DayOverride].
 * @property lastSwapDate The ISO date the last swap write touched, or null when the last write
 *   was not a swap. It exists for `firestore.rules`: Rules cannot iterate a map, so a swap write
 *   names the one date it changes and the rule then requires the diff to affect only that key —
 *   which makes naming the wrong date self-defeating rather than merely useless.
 * @property lastModifiedKind What the last write to this document actually changed. It exists
 *   because `firestore.rules` requires every update to stamp [lastModifiedBy] with the caller,
 *   so a swap write cannot leave the field alone — and without this marker the co-parent's
 *   device would read that stamp as a pattern change and raise the "the schedule changed under
 *   you" banner for a day nobody has agreed to yet. See [CustodyWriteKind].
 */
data class SharedCustody(
    val model: CustodyModel,
    val lastModifiedBy: String,
    val lastModifiedAtMillis: Long,
    val lastModifiedAt: String,
    val createdAt: String,
    val repeatYearly: Boolean = true,
    val proposal: CustodyProposal? = null,
    val lastDecision: CustodyDecision? = null,
    val dayOverrides: Map<String, DayOverride> = emptyMap(),
    val lastSwapDate: String? = null,
    val lastModifiedKind: CustodyWriteKind = CustodyWriteKind.PATTERN
)

/**
 * What a write to the shared custody document changed.
 *
 * Needed because two rules pull in opposite directions. `firestore.rules` requires every update
 * to carry `lastModifiedBy == request.auth.uid`, so **no** write can leave that field as it was;
 * meanwhile `CustodyChangeAnnouncement` reads exactly that field to decide whether the co-parent
 * changed the agreed schedule. Without a marker, offering or answering a one-off swap — which
 * changes no pattern at all — would raise "your co-parent changed the schedule" on the other
 * phone, about a day neither parent has agreed to. A swap has its own channel: the inbox, and the
 * arrows on the grid.
 *
 * [PATTERN] is the default, and that is the right default for a document written by a build that
 * predates this field: those builds only ever wrote patterns.
 */
enum class CustodyWriteKind {
    /** The agreed pattern itself changed. The banner's business. */
    PATTERN,

    /** Only [SharedCustody.dayOverrides] changed — an offer, or an answer to one. */
    SWAP
}
