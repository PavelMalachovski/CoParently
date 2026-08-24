package com.coparently.app.domain.custody

/**
 * Where a single day's override stands.
 *
 * Only [ACCEPTED] changes whose day it is. [PENDING] and [DECLINED] are markers — the calendar
 * draws them, but `CustodyResolver` steps straight past them to the agreed pattern. Letting a
 * pending swap move a day would show both parents a day neither of them agreed to.
 */
enum class DayOverrideStatus {
    /** Offered, and waiting for the other parent's answer. */
    PENDING,

    /** Agreed. This is the only status that moves a day. */
    ACCEPTED,

    /** Turned down. Kept so the parent who offered learns the outcome. */
    DECLINED
}

/**
 * One day offered to the other parent, outside the agreed pattern.
 *
 * Deliberately not a `ChangeRequest` and not a `CustodyProposal`. A `ChangeRequest` points at an
 * `eventId`, and a custody day is a property of the pattern with no row to point at. A
 * `CustodyProposal` replaces the whole pattern, so expressing "just next Saturday" as one would
 * mean inventing a pattern that differs in a single day and never repeats — and accepting it
 * would overwrite the arrangement the parents agreed.
 *
 * Carried in a map keyed by ISO date on the pair's shared `custody_models` document. A map rather
 * than a list because one override per date *is* the semantics: "we swap next Saturday" cannot
 * mean two things at once, and a map makes that structurally true instead of a rule some caller
 * has to remember. It also makes the calendar's lookup an O(1) read on a date it already holds,
 * for every cell it draws.
 *
 * @property toParent The slot taking the day — `"mom"` or `"dad"`, the two schema slot ids that
 *   are never renamed. Never a display name: `ParentLabels` resolves those.
 * @property requestedBy Firebase UID of the parent who offered the day. `firestore.rules` requires
 *   this to be the caller, and it is what stops either parent deciding their own offer.
 * @property requestedAt ISO date-time string, as everywhere else in this Firestore schema — dates
 *   cross the wire as strings, not as Firestore timestamps.
 * @property status Where the offer stands; see [DayOverrideStatus].
 * @property decidedBy Firebase UID of whoever answered, or null while [status] is
 *   [DayOverrideStatus.PENDING].
 * @property decidedAt ISO date-time of that answer, or null while pending.
 * @property note Optional free text from either side. Blank is stored as null rather than as an
 *   empty string, so the UI has one thing to test for.
 * @property groupId Ties the days of one multi-day offer together, or null for a single day and
 *   for every entry written before groups existed.
 *
 *   Grouping is a **client-side** fact and deliberately so. `firestore.rules` can validate a diff
 *   that names exactly one date (Rules cannot iterate a map), so a run of days is still written
 *   one date at a time and stays one entry per date — which is the semantics the map exists to
 *   make structurally true. What the group id buys is the half the reporter actually asked for:
 *   the co-parent gets one card, one dialog and one push saying "5 days", instead of five of
 *   each.
 *
 *   A null id means "its own group of one", which is what makes every pre-existing entry read
 *   correctly with no migration and no backfill.
 */
data class DayOverride(
    val toParent: String,
    val requestedBy: String,
    val requestedAt: String,
    val status: DayOverrideStatus = DayOverrideStatus.PENDING,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val note: String? = null,
    val groupId: String? = null
) {
    /** True while this offer is still waiting for an answer. */
    val isPending: Boolean get() = status == DayOverrideStatus.PENDING

    /** True when this offer actually moves the day. */
    val isAccepted: Boolean get() = status == DayOverrideStatus.ACCEPTED
}
