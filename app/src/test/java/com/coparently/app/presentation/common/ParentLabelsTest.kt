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
        assertEquals("Olya", parentLabel("mom", me, coParent, "You", "Co-parent"))
    }

    @Test
    fun `the other slot is the co-parent's name`() {
        assertEquals("Pavel", parentLabel("dad", me, coParent, "You", "Co-parent"))
    }

    @Test
    fun `my slot with no name stored falls back to You`() {
        assertEquals(
            "You",
            parentLabel("mom", me.copy(name = ""), coParent, "You", "Co-parent")
        )
    }

    @Test
    fun `the other slot with no co-parent falls back to Co-parent`() {
        assertEquals("Co-parent", parentLabel("dad", me, null, "You", "Co-parent"))
    }

    @Test
    fun `the other slot with a nameless co-parent falls back to Co-parent`() {
        assertEquals(
            "Co-parent",
            parentLabel("dad", me, coParent.copy(name = "   "), "You", "Co-parent")
        )
    }

    @Test
    fun `an unknown signed-in user resolves both slots by fallback`() {
        assertEquals("You", parentLabel("mom", null, null, "You", "Co-parent"))
        assertEquals("Co-parent", parentLabel("dad", null, null, "You", "Co-parent"))
    }

    @Test
    fun `a slot that is neither mine nor my co-parent's is the co-parent fallback`() {
        // Defensive: a stale row could carry a slot string from a future schema.
        assertEquals("Co-parent", parentLabel("guardian", me, coParent, "You", "Co-parent"))
    }
}
