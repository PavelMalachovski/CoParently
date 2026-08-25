package com.coparently.app.domain.family

/**
 * The id of one co-parenting relationship: two adults, and everything they share.
 *
 * Derived from the two UIDs rather than generated, so both devices arrive at the same id with no
 * query and no coordination, and creating the family is idempotent. Randomly generated ids are
 * what once settled two phones on separate chat threads.
 *
 * **This key already existed twice.** `CustodyKey` and `ConversationKey` are the same function
 * written out separately — sort the two uids, join them — which is why `custody_models/{id}`,
 * `family_settings/{id}` and `conversations/{id}` are already, byte for byte, keyed by family.
 * That was invisible while a person could have only one partner, because there was nothing to
 * tell apart. Both now delegate here, so the identity is stated once and testable, and so the
 * next subsystem that needs a family id does not write it a third time.
 *
 * The separator is part of the stored schema — every one of those documents is named with it —
 * and is never changed.
 */
object FamilyKey {

    /** Separator between the two sorted UIDs; not a Firebase UID character. */
    const val SEPARATOR = "__"

    /**
     * Returns the family id for the pair [uidA]/[uidB], in either order.
     *
     * @throws IllegalArgumentException if either uid is blank, the two are equal — a person
     *   co-parents with somebody else, and silently producing an id for that case would hide a
     *   caller bug — or either uid contains [SEPARATOR]. Without that last check
     *   `of("x__y", "z")` and `of("x", "y__z")` both join to `"x__y__z"`: two different pairs
     *   colliding on one document, which is exactly what this function exists to prevent. Real
     *   Firebase UIDs never contain `_`, so it only ever fires on a caller bug or a test double.
     */
    fun of(uidA: String, uidB: String): String {
        require(uidA.isNotBlank() && uidB.isNotBlank()) { "Both uids must be non-blank" }
        require(uidA != uidB) { "A person has no co-parenting relationship with themselves" }
        require(!uidA.contains(SEPARATOR) && !uidB.contains(SEPARATOR)) {
            "A uid must not contain the separator '$SEPARATOR'"
        }
        return listOf(uidA, uidB).sorted().joinToString(SEPARATOR)
    }

    /**
     * The family id for [uid] and [partnerId], or null when there is no relationship yet.
     *
     * The shape every writer actually needs. A record created before its owner pairs belongs to
     * nobody but them, and null is what says so — so this returns null rather than inventing an
     * id for a pair of one. Callers that stamp a record all reach for the same three questions
     * (is there a uid, is there a partner, is the partner blank), and having them in one place
     * is what stops one of them answering differently.
     */
    fun orNull(uid: String?, partnerId: String?): String? {
        val mine = uid?.takeIf { it.isNotBlank() } ?: return null
        val theirs = partnerId?.takeIf { it.isNotBlank() } ?: return null
        if (mine == theirs) return null
        return of(mine, theirs)
    }

    /**
     * The two uids a family id is built from, or null when [familyId] is not one.
     *
     * The inverse matters because a rule and a callable both need it: `custody_models`'
     * `allow get` already splits the id to check the caller is one of the two named uids, on a
     * document that may not exist yet.
     */
    fun membersOf(familyId: String): Pair<String, String>? {
        val parts = familyId.split(SEPARATOR)
        if (parts.size != 2) return null
        if (parts.any { it.isBlank() }) return null
        return parts[0] to parts[1]
    }
}
