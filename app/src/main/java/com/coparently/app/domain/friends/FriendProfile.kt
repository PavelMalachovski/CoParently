package com.coparently.app.domain.friends

/**
 * A trusted third person a family lets in beside the two parents — a guardian, a friend, a
 * grandparent — with their own account and their own self-authored profile.
 *
 * A friend **sits beside the two-slot parent model and never occupies a slot** (the rule
 * [com.coparently.app.domain.guests.GuestGrant] states for a child-record guest). `"mom"`/`"dad"`
 * are the two schema slot ids and are never renamed; a friend is neither, and
 * `acceptPairingInvitation` refuses a friend invitation outright so one can never become one.
 *
 * The profile is authored by the friend about themselves — the two parents read it but never
 * write it, so nobody puts words in a grandmother's mouth. [familyParents] is the pair who may
 * read it, stamped once and immutable thereafter; it is what the security rule keys the parents'
 * read on without a per-document `get()`.
 *
 * @property uid The friend's own Firebase UID — the profile document's id.
 * @property name The friend's name, as they give it. Never derived from an email.
 * @property role What they are to the family; see [FriendRole].
 * @property phones Phone numbers, in the order the friend listed them. Blank entries are dropped
 *   before storage so the UI has one thing to test for.
 * @property bloodGroup The friend's blood group, or null when they did not give one. Free text,
 *   not an enum: the field exists for somebody to read aloud in an emergency, not to validate.
 * @property photoUrl A Firebase Storage download URL for the friend's photo, or null.
 * @property familyParents The two parent UIDs allowed to read this profile.
 */
data class FriendProfile(
    val uid: String,
    val name: String,
    val role: FriendRole = FriendRole.FRIEND,
    val phones: List<String> = emptyList(),
    val bloodGroup: String? = null,
    val photoUrl: String? = null,
    val familyParents: List<String> = emptyList()
)

/**
 * What a friend is to the family. A label: the enum name is the stored value and is never shown,
 * the UI resolves it to a localized string.
 */
enum class FriendRole {
    /** A guardian who may care for the child. */
    GUARDIAN,

    /** A friend of the family. */
    FRIEND,

    /** A grandparent. */
    GRANDPARENT
}

/**
 * A friend's live read access to one family's calendar.
 *
 * Mirrors `calendar_friends/{friendUid}`. Held centrally rather than fanned out into every
 * event's audience, so admitting or revoking a friend is one write and no event document is ever
 * rewritten — see the events read rule in `firestore.rules`.
 *
 * @property friendUid Whose access this is; the document's id.
 * @property name The friend's display name at the time the grant was made, so a parent's
 *   "who can see this" list can name them without reading their profile.
 * @property familyParents The two parents whose events the friend may read.
 * @property grantedBy Which parent admitted them. Required by the rule to be the caller.
 * @property grantedAtMillis When access began, epoch millis.
 * @property expiresAtMillis When access ends, epoch millis. Never absent and never zero — the one
 *   default this feature must not have is "forever", which is why [CalendarFriendPolicy] treats
 *   a missing value as expired rather than as unlimited.
 */
data class CalendarFriendGrant(
    val friendUid: String,
    val name: String,
    val familyParents: List<String>,
    val grantedBy: String,
    val grantedAtMillis: Long,
    val expiresAtMillis: Long
)

/**
 * Whether a calendar-friend grant is still live — the single statement the app, the security
 * rule and (when one is added) the sweep must all agree with.
 *
 * The same shape and the same strict comparison as
 * [com.coparently.app.domain.guests.GuestGrantPolicy]: a grant expiring at noon is inactive at
 * noon for all of them. **Fails closed** — anything that is not a positive future instant is
 * expired, never unlimited.
 */
object CalendarFriendPolicy {

    /** The default length of a grant when a parent does not choose one. */
    const val DEFAULT_DURATION_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * @param grant The grant to judge, or null when there is none.
     * @param nowMillis The instant to judge it at.
     * @return true when [grant] still admits a read.
     */
    fun isActive(grant: CalendarFriendGrant?, nowMillis: Long): Boolean =
        grant != null && grant.expiresAtMillis > 0 && grant.expiresAtMillis > nowMillis

    /** The live grants among [grants], at [nowMillis]. */
    fun active(grants: List<CalendarFriendGrant>, nowMillis: Long): List<CalendarFriendGrant> =
        grants.filter { isActive(it, nowMillis) }
}
