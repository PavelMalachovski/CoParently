package com.coparently.app.presentation.event

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [showParentOwnerSelector] decides whether AddEditEventScreen's parent-owner picker renders.
 *
 * These four cases are the ones a code-review round pinned after the first fix only closed the
 * gap `Parents.loaded` can see: `parentOwner` starts null and is filled in by a `LaunchedEffect`
 * that runs one composition *after* `parents` resolves, so gating on `parentOwner == null` alone
 * still let the picker flash open for a frame for an unpaired account whose slot had already
 * resolved. Falling back to `currentUserSlot` — the value that effect is about to assign —
 * closes it by construction instead of by narrowing the window.
 */
class AddEditEventScreenTest {

    @Test
    fun `hidden before the upstream has emitted once, regardless of the rest`() {
        assertFalse(
            showParentOwnerSelector(
                parentsLoaded = false,
                isPaired = false,
                parentOwner = null,
                currentUserSlot = null
            )
        )
        assertFalse(
            showParentOwnerSelector(
                parentsLoaded = false,
                isPaired = true,
                parentOwner = null,
                currentUserSlot = "mom"
            )
        )
    }

    @Test
    fun `a family of one whose own slot already resolved is hidden immediately, not after a frame`() {
        // The case the first fix round left open: parentOwner has not caught up to
        // currentUserSlot yet (the LaunchedEffect that copies it hasn't run this composition),
        // but there is nobody to choose between either way.
        assertFalse(
            showParentOwnerSelector(
                parentsLoaded = true,
                isPaired = false,
                parentOwner = null,
                currentUserSlot = "mom"
            )
        )
    }

    @Test
    fun `an account with no Room profile row is shown so it has an escape from a disabled Save`() {
        assertTrue(
            showParentOwnerSelector(
                parentsLoaded = true,
                isPaired = false,
                parentOwner = null,
                currentUserSlot = null
            )
        )
    }

    @Test
    fun `a legacy pair is shown even though the co-parent's slot is not known yet`() {
        assertTrue(
            showParentOwnerSelector(
                parentsLoaded = true,
                isPaired = true,
                parentOwner = null,
                currentUserSlot = "mom"
            )
        )
    }

    @Test
    fun `once an owner is assigned - a loaded event, say - an unpaired account no longer sees it`() {
        assertFalse(
            showParentOwnerSelector(
                parentsLoaded = true,
                isPaired = false,
                parentOwner = "dad",
                currentUserSlot = null
            )
        )
    }
}
