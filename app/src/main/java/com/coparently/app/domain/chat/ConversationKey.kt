package com.coparently.app.domain.chat

import com.coparently.app.domain.family.FamilyKey

/**
 * The id of the single conversation shared by a pair of co-parents.
 *
 * **The family id, seen from the chat subsystem.** [FamilyKey] is the one definition; this name
 * is kept because `conversations/{id}` is what the call sites are addressing. Deriving the id
 * rather than generating one is what removes the failure this replaced: two phones independently
 * generating random ids and settling on separate threads.
 */
object ConversationKey {

    /**
     * Returns the conversation id for the pair [uidA]/[uidB], in either order.
     *
     * @throws IllegalArgumentException on a blank uid, two equal uids, or a uid containing the
     *   separator — see [FamilyKey.of], which this delegates to.
     */
    fun of(uidA: String, uidB: String): String = FamilyKey.of(uidA, uidB)
}
