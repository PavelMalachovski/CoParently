package com.coparently.app.domain.custody

import com.coparently.app.domain.family.FamilyKey

/**
 * The id of the single custody document shared by a pair of co-parents.
 *
 * **The family id, seen from the custody subsystem.** [FamilyKey] is the one definition; this
 * name is kept because `custody_models/{id}` is what the call sites are addressing, and reading
 * `CustodyKey.of` there says which document is meant. The two were separate implementations of
 * the same function until it mattered that they were the same — see [FamilyKey].
 */
object CustodyKey {

    /**
     * Returns the custody document id for the pair [uidA]/[uidB], in either order.
     *
     * @throws IllegalArgumentException on a blank uid, two equal uids, or a uid containing the
     *   separator — see [FamilyKey.of], which this delegates to.
     */
    fun of(uidA: String, uidB: String): String = FamilyKey.of(uidA, uidB)
}
