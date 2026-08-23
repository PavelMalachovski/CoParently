package com.coparently.app.data.sync

import com.coparently.app.domain.guests.GuestGrant

/**
 * The guest grants on a child-info document, read from and written to the wire.
 *
 * A pure helper for the same reason [ChildInfoAudience] and [ChildInfoPhotos] are: the child
 * document is built and read in seven places, and a field that behaves slightly differently in
 * two of them is a defect nothing catches.
 *
 * It matters more here than for the other two. A grant is an **access decision**, and the same
 * decision is made by `firestore.rules` and by the scheduled sweep, neither of which can call
 * this. Anything this reads more leniently than they do is a guest the app shows as gone while
 * the rules still serve them — or, worse, the other way round.
 */
object ChildInfoGuests {

    /**
     * The grants on a document, keyed by the guest's uid.
     *
     * Never throws and never returns null. A grant missing any of its fields is **dropped
     * entirely** rather than defaulted into existence: a half-read grant is a person with access
     * whose name or expiry the parent cannot see, and the honest answer to "who did I let in" is
     * not a row of blanks. `expiresAtMillis` in particular defaults to 0, which
     * `GuestGrantPolicy` reads as expired — so even a grant that slips through reads as ended
     * rather than as permanent.
     *
     * @param value The document's `guests` field, however Firestore deserialised it.
     */
    fun decode(value: Any?): Map<String, GuestGrant> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        return raw.entries.mapNotNull { (key, entry) ->
            val uid = key as? String ?: return@mapNotNull null
            val fields = entry as? Map<*, *> ?: return@mapNotNull null
            val name = fields["name"] as? String ?: return@mapNotNull null
            val grantedBy = fields["grantedBy"] as? String ?: return@mapNotNull null
            uid to GuestGrant(
                uid = uid,
                name = name,
                grantedBy = grantedBy,
                grantedAtMillis = millis(fields["grantedAtMillis"]),
                expiresAtMillis = millis(fields["expiresAtMillis"])
            )
        }.toMap()
    }

    /**
     * The grants as a document field.
     *
     * The uid is the map key *and* is left off the value: it is already the key, and a copy that
     * can disagree with it is a copy that eventually will.
     */
    fun encode(grants: Map<String, GuestGrant>): Map<String, Any?> =
        grants.mapValues { (_, grant) ->
            mapOf(
                "name" to grant.name,
                "grantedBy" to grant.grantedBy,
                "grantedAtMillis" to grant.grantedAtMillis,
                "expiresAtMillis" to grant.expiresAtMillis
            )
        }

    /**
     * A stored time as epoch millis.
     *
     * Firestore hands back a `Long` for a number written by another client and a `Double` for
     * one that went through JSON, so both are accepted. Anything else is 0 — which
     * [com.coparently.app.domain.guests.GuestGrantPolicy] reads as expired, never as absent.
     */
    private fun millis(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        else -> 0L
    }
}
