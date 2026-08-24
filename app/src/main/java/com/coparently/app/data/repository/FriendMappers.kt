package com.coparently.app.data.repository

import com.coparently.app.domain.friends.CalendarFriendGrant
import com.coparently.app.domain.friends.FriendProfile
import com.coparently.app.domain.friends.FriendRole

/**
 * Reading the two friend documents back out of Firestore, and writing them.
 *
 * Pure and separate from the repository so the decoding can be tested without Firestore — the
 * same shape `ChildInfoGuests` and `DayOverrideJson` take, and for the same reason: this is the
 * path that decides whether a third person can see a family's calendar, and every field on it
 * arrives as `Any?` from a document some other build may have written.
 *
 * **Every decode drops rather than guesses.** A grant with no parents, no expiry or the wrong
 * shape is not a grant — returning a half-parsed one would admit a reader the rules would then
 * have to refuse, which is a worse failure than showing nothing.
 */
object FriendMappers {

    /**
     * A [CalendarFriendGrant] from its stored map, or null when the document cannot describe one.
     *
     * @param friendUid The document's id — the grant does not repeat it in its own fields.
     * @param data The document's data.
     */
    fun grantFrom(friendUid: String, data: Map<String, Any?>?): CalendarFriendGrant? {
        if (data == null || friendUid.isBlank()) return null
        val parents = (data["familyParents"] as? List<*>)
            ?.mapNotNull { (it as? String)?.takeIf { uid -> uid.isNotBlank() } }
            .orEmpty()
        // Exactly two, always: the events query is `whereIn` over this list, and a list of one
        // or three would either under-fetch or reach past the family.
        if (parents.size != 2) return null
        val expiresAtMillis = (data["expiresAtMillis"] as? Number)?.toLong() ?: 0L
        if (expiresAtMillis <= 0L) return null
        return CalendarFriendGrant(
            friendUid = friendUid,
            name = (data["name"] as? String).orEmpty(),
            familyParents = parents,
            grantedBy = (data["grantedBy"] as? String).orEmpty(),
            grantedAtMillis = (data["grantedAtMillis"] as? Number)?.toLong() ?: 0L,
            expiresAtMillis = expiresAtMillis
        )
    }

    /**
     * A [FriendProfile] from its stored map, or null when the document cannot describe one.
     *
     * A profile with no name is dropped: the parents' list identifies a friend by name, so a
     * nameless one is access nobody can recognise in order to revoke it — the same reasoning
     * `guestName` applies server-side.
     */
    fun profileFrom(uid: String, data: Map<String, Any?>?): FriendProfile? {
        if (data == null || uid.isBlank()) return null
        val name = (data["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val role = (data["role"] as? String)
            ?.let { stored -> FriendRole.entries.firstOrNull { it.name == stored } }
            ?: FriendRole.FRIEND
        return FriendProfile(
            uid = uid,
            name = name,
            role = role,
            phones = (data["phones"] as? List<*>)
                ?.mapNotNull { (it as? String)?.takeIf { phone -> phone.isNotBlank() } }
                .orEmpty(),
            bloodGroup = (data["bloodGroup"] as? String)?.takeIf { it.isNotBlank() },
            photoUrl = (data["photoUrl"] as? String)?.takeIf { it.isNotBlank() },
            familyParents = (data["familyParents"] as? List<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()
        )
    }

    /**
     * The stored map for a profile.
     *
     * Blank phones are dropped and a blank blood group becomes an absent key rather than an empty
     * string, so the reader has one thing to test for. `familyParents` is written on create and
     * is immutable afterwards — the rule refuses a write that changes it.
     */
    fun profileToMap(profile: FriendProfile): Map<String, Any> = buildMap {
        put("uid", profile.uid)
        put("name", profile.name)
        put("role", profile.role.name)
        put("phones", profile.phones.filter { it.isNotBlank() })
        put("familyParents", profile.familyParents)
        profile.bloodGroup?.takeIf { it.isNotBlank() }?.let { put("bloodGroup", it) }
        profile.photoUrl?.takeIf { it.isNotBlank() }?.let { put("photoUrl", it) }
    }
}
