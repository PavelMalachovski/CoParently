package com.coparently.app.presentation.common

import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParentLabelsTest {

    private val me = NamedParent(
        uid = "u1",
        slot = "mom",
        name = "Olya"
    )
    private val coParent = NamedParent(
        uid = "u2",
        slot = "dad",
        name = "Pavel"
    )

    @Test
    fun `my own slot is my name`() {
        assertEquals(
            "Olya",
            parentLabel("mom", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `the other slot is the co-parent's name`() {
        assertEquals(
            "Pavel",
            parentLabel("dad", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `my slot with no name stored falls back to You`() {
        assertEquals(
            "You",
            parentLabel("mom", me.copy(name = ""), coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `an unmatched slot with no co-parent resolves to unknown fallback`() {
        // When the co-parent hasn't loaded, their slot is unknown, not guessed.
        assertEquals(
            "Parent",
            parentLabel("dad", me, null, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `the other slot with a nameless co-parent falls back to Co-parent`() {
        assertEquals(
            "Co-parent",
            parentLabel("dad", me, coParent.copy(name = "   "), "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `both unloaded users resolve slots to the unknown fallback`() {
        assertEquals(
            "Parent",
            parentLabel("mom", null, null, "You", "Co-parent", "Parent")
        )
        assertEquals(
            "Parent",
            parentLabel("dad", null, null, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `an unmatched slot with known parents resolves to the unknown fallback`() {
        // Defensive: a stale row could carry a slot string from a future schema.
        assertEquals(
            "Parent",
            parentLabel("guardian", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `a loaded co-parent does not cause the unknown me slot to be guessed`() {
        // Regression test: when me hasn't loaded, we must not guess "mom" is their slot.
        // With the bug (`?: "mom"`), calling parentLabel("mom", null, coParent) would
        // incorrectly return youFallback, guessing that an unknown me has slot "mom".
        assertEquals(
            "Parent",
            parentLabel("mom", null, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `a co-parent sharing my slot never takes a slot that is not theirs`() {
        // Every pair created before slot assignment shipped has both parents on "mom". Until
        // the backfill separates them the co-parent is not identifiable, and the honest answer
        // for the empty slot is the unknown fallback - never "whichever slot is left over",
        // which would put the co-parent's name on days that are not theirs.
        val sameSlot = coParent.copy(slot = "mom")
        assertEquals(
            "Olya",
            parentLabel("mom", me, sameSlot, "You", "Co-parent", "Parent")
        )
        assertEquals(
            "Parent",
            parentLabel("dad", me, sameSlot, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `a user projects to their stored slot and name`() {
        val user = User(
            id = "u1",
            email = "a@b.c",
            name = "Olya",
            role = "mom",
            colorCode = "#FF4081"
        )
        assertEquals(me, user.asNamedParent())
    }

    @Test
    fun `a partner with a stored slot projects to it`() {
        val partner = PartnerSummary(
            id = "u2",
            name = "Pavel",
            email = "d@e.f",
            pairedSinceMillis = 1L,
            role = "dad"
        )
        assertEquals(coParent, partner.asNamedParent())
    }

    @Test
    fun `a partner with no stored slot does not project at all`() {
        // Their users document carries no slot yet. Projecting them onto the free slot is the
        // guess this branch exists to remove, so there is nothing to project.
        val partner = PartnerSummary(
            id = "u2",
            name = "Pavel",
            email = "d@e.f",
            pairedSinceMillis = 1L,
            role = null
        )
        assertNull(partner.asNamedParent())
    }

    @Test
    fun `my own uid resolves to my name`() {
        assertEquals(
            "Olya",
            parentLabelByUid("u1", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `the co-parent's uid resolves to their name`() {
        assertEquals(
            "Pavel",
            parentLabelByUid("u2", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `a uid matching neither parent resolves to the unknown fallback`() {
        assertEquals(
            "Parent",
            parentLabelByUid("some-stranger-uid", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `a co-parent write on an unmigrated pair still names the co-parent, not me`() {
        // Every pair created before slot assignment shipped has both parents reading "mom".
        // Resolving lastModifiedBy through the slot (parentLabel) would collapse both uids onto
        // "mom" and report the co-parent's write as mine; resolving the uid directly does not.
        val sameSlotCoParent = coParent.copy(slot = "mom")
        assertEquals(
            "Pavel",
            parentLabelByUid("u2", me, sameSlotCoParent, "You", "Co-parent", "Parent")
        )
        assertEquals(
            "Olya",
            parentLabelByUid("u1", me, sameSlotCoParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `my uid with no name stored falls back to You`() {
        assertEquals(
            "You",
            parentLabelByUid("u1", me.copy(name = ""), coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `the co-parent's uid with no name stored falls back to Co-parent`() {
        assertEquals(
            "Co-parent",
            parentLabelByUid("u2", me, coParent.copy(name = "   "), "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `an unloaded co-parent does not cause a matching uid to be guessed`() {
        assertEquals(
            "Parent",
            parentLabelByUid("u2", me, null, "You", "Co-parent", "Parent")
        )
    }
}
