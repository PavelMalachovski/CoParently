package com.coparently.app.presentation.common

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ParentNames.isKnown] is what the parent-selector cards in `AddEditEventScreen` use to decide
 * between a name-shaped label and an ordinal one - see [ParentLabelsTest] for the question
 * [parentLabel] itself answers, which this deliberately does not change.
 */
class ParentNamesTest {

    private val me = NamedParent(uid = "u1", slot = "mom", name = "Olya")
    private val coParent = NamedParent(uid = "u2", slot = "dad", name = "Pavel")

    private fun parentNames(parents: Parents) = ParentNames(
        parents = parents,
        youFallback = "You",
        coParentFallback = "Co-parent",
        unknownFallback = "Parent"
    )

    @Test
    fun `neither slot is known when both parents are null`() {
        val names = parentNames(Parents())
        assertFalse(names.isKnown("mom"))
        assertFalse(names.isKnown("dad"))
    }

    @Test
    fun `a slot matching a named parent is known`() {
        val names = parentNames(Parents(me = me, coParent = coParent))
        assertTrue(names.isKnown("mom"))
        assertTrue(names.isKnown("dad"))
    }

    @Test
    fun `a slot matching neither parent is not known`() {
        val names = parentNames(Parents(me = me, coParent = null))
        assertFalse(names.isKnown("dad"))
    }

    @Test
    fun `labelForUid resolves the co-parent's uid even on an unmigrated pair sharing a slot`() {
        // The regression labelFor cannot be trusted with: both parents on "mom" would collapse
        // to the same slot, but their uids are always distinct.
        val sameSlotCoParent = coParent.copy(slot = "mom")
        val names = parentNames(Parents(me = me, coParent = sameSlotCoParent))
        assertEquals("Pavel", names.labelForUid("u2"))
        assertEquals("Olya", names.labelForUid("u1"))
    }

    @Test
    fun `labelForUid falls back to unknown for a null uid`() {
        val names = parentNames(Parents(me = me, coParent = coParent))
        assertEquals("Parent", names.labelForUid(null))
    }
}
