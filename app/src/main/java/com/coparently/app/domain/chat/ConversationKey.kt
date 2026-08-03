package com.coparently.app.domain.chat

/**
 * The id of the single conversation shared by a pair of co-parents.
 *
 * Derived from the two UIDs rather than generated, so both devices arrive at the same id
 * with no query and no coordination. That makes creating the conversation idempotent and
 * removes the failure this replaces: two phones independently generating random ids and
 * settling on separate threads.
 */
object ConversationKey {

    /** Separator between the two sorted UIDs; not a Firebase UID character. */
    private const val SEPARATOR = "__"

    /**
     * Returns the conversation id for the pair [uidA]/[uidB], in either order.
     *
     * @throws IllegalArgumentException if either uid is blank, the two are equal — a user has
     *   no conversation with themselves, and silently producing an id for that case would hide
     *   a caller bug — or either uid contains [SEPARATOR]. Without that last check
     *   `of("x__y", "z")` and `of("x", "y__z")` would both join to `"x__y__z"`: different pairs
     *   colliding on the same id, which is exactly the failure this function exists to prevent.
     *   Real Firebase UIDs never contain `_`, so this only ever fires on a caller bug or a
     *   non-Firebase test double.
     */
    fun of(uidA: String, uidB: String): String {
        require(uidA.isNotBlank() && uidB.isNotBlank()) { "Both uids must be non-blank" }
        require(uidA != uidB) { "A user cannot have a conversation with themselves" }
        require(!uidA.contains(SEPARATOR) && !uidB.contains(SEPARATOR)) {
            "A uid must not contain the separator '$SEPARATOR'"
        }
        return listOf(uidA, uidB).sorted().joinToString(SEPARATOR)
    }
}
