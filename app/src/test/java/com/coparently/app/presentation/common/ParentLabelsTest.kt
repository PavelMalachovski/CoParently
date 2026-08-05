package com.coparently.app.presentation.common

import com.coparently.app.domain.model.User
import org.junit.Test
import kotlin.test.assertEquals

class ParentLabelsTest {

    private val me = User(
        id = "u1", email = "a@b.c", name = "Olya", role = "mom", colorCode = "#FF4081"
    )
    private val coParent = User(
        id = "u2", email = "d@e.f", name = "Pavel", role = "dad", colorCode = "#2196F3"
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
        // incorrectly return youFallback, guessing that an unknown me has role "mom".
        assertEquals(
            "Parent",
            parentLabel("mom", null, coParent, "You", "Co-parent", "Parent")
        )
    }
}
