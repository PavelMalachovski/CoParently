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
     * @throws IllegalArgumentException if either uid is blank, or the two are equal —
     *   a user has no conversation with themselves, and silently producing an id for
     *   that case would hide a caller bug.
     */
    fun of(uidA: String, uidB: String): String {
        require(uidA.isNotBlank() && uidB.isNotBlank()) { "Both uids must be non-blank" }
        require(uidA != uidB) { "A user cannot have a conversation with themselves" }
        return listOf(uidA, uidB).sorted().joinToString(SEPARATOR)
    }
}
