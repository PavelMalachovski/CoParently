package com.coparently.app.domain.custody

/**
 * The id of the single custody document shared by a pair of co-parents.
 *
 * Derived from the two UIDs rather than generated, so both devices arrive at the same id with
 * no query and no coordination, and creating the document is idempotent. The same shape as
 * [com.coparently.app.domain.chat.ConversationKey], for the same reason: randomly generated
 * ids are what once settled the two phones on separate chat threads.
 */
object CustodyKey {

    /** Separator between the two sorted UIDs; not a Firebase UID character. */
    private const val SEPARATOR = "__"

    /**
     * Returns the custody document id for the pair [uidA]/[uidB], in either order.
     *
     * @throws IllegalArgumentException if either uid is blank, the two are equal — a user has
     *   no custody arrangement with themselves — or either contains [SEPARATOR]. Without that
     *   last check `of("x__y", "z")` and `of("x", "y__z")` both join to `"x__y__z"`: different
     *   pairs colliding on one document, which is exactly what this function prevents.
     */
    fun of(uidA: String, uidB: String): String {
        require(uidA.isNotBlank() && uidB.isNotBlank()) { "Both uids must be non-blank" }
        require(uidA != uidB) { "A user has no custody arrangement with themselves" }
        require(!uidA.contains(SEPARATOR) && !uidB.contains(SEPARATOR)) {
            "A uid must not contain the separator '$SEPARATOR'"
        }
        return listOf(uidA, uidB).sorted().joinToString(SEPARATOR)
    }
}
