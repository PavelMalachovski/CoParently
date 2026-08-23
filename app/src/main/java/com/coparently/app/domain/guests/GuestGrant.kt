package com.coparently.app.domain.guests

/**
 * One person who may read a child's record without being a parent of them.
 *
 * A grandparent for a weekend, in the item's own example. A guest sits **beside** the two-slot
 * parent model and never occupies a slot: they are a uid in the record's `sharedWith`, which is
 * what makes the existing read rule work unchanged, plus an entry in the record's `guests` map,
 * which is what makes them a guest rather than a parent.
 *
 * @property uid The guest's Firebase UID.
 * @property name What to call them in the list on the child's screen. Stored on the grant rather
 *   than read from their profile: a parent must be able to see who they let in without being
 *   given read access to that person's account.
 * @property grantedBy The parent who let them in.
 * @property grantedAtMillis When, as epoch milliseconds — see [expiresAtMillis].
 * @property expiresAtMillis When the grant ends, as epoch milliseconds.
 *
 *   **Epoch millis rather than the ISO string the rest of this schema uses, and the reason is
 *   `firestore.rules`.** Three things have to agree about whether a grant is still good: the app,
 *   the security rule, and the scheduled sweep. Rules have no way to parse a string into a
 *   timestamp — there is no such function in the language — so an ISO `expiresAt` could not be
 *   compared to `request.time` at all, and the rule could only ever check that a guest *is* a
 *   guest, never that their grant still stands. Millis is the one representation all three can
 *   compare. It is also the move `Message.sentAtMillis` already made, and CLAUDE.md records the
 *   custody document's zone-less `lastModifiedAt` as the mistake not to repeat.
 *
 *   There is no "no expiry" value. The expiry is mandatory, because a permanent grant is the
 *   wrong default for an access token handed out casually and a separated parent will not
 *   remember to revoke it.
 */
data class GuestGrant(
    val uid: String,
    val name: String,
    val grantedBy: String,
    val grantedAtMillis: Long,
    val expiresAtMillis: Long
)
