package com.coparently.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollPolicyTest {

    @Test
    fun `an empty thread has nowhere to scroll`() {
        assertNull(
            ChatScrollPolicy.targetIndex(
                entryCount = 0,
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                initialJumpDone = false
            )
        )
    }

    @Test
    fun `a long thread jumps to the newest message on first composition`() {
        assertEquals(
            39,
            ChatScrollPolicy.targetIndex(
                entryCount = 40,
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                initialJumpDone = false
            )
        )
    }

    @Test
    fun `a thread short enough to fit still targets its last entry`() {
        assertEquals(
            1,
            ChatScrollPolicy.targetIndex(
                entryCount = 2,
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                initialJumpDone = false
            )
        )
    }

    @Test
    fun `a message arriving while reading history does not move the view`() {
        assertNull(
            ChatScrollPolicy.targetIndex(
                entryCount = 40,
                firstVisibleIndex = 0,
                lastVisibleIndex = 6,
                initialJumpDone = true
            )
        )
    }

    @Test
    fun `a message arriving while parked at the bottom scrolls down`() {
        assertEquals(
            40,
            ChatScrollPolicy.targetIndex(
                entryCount = 41,
                firstVisibleIndex = 33,
                lastVisibleIndex = 39,
                initialJumpDone = true
            )
        )
    }

    @Test
    fun `nothing to do when already at the bottom and nothing arrived`() {
        assertNull(
            ChatScrollPolicy.targetIndex(
                entryCount = 41,
                firstVisibleIndex = 33,
                lastVisibleIndex = 40,
                initialJumpDone = true
            )
        )
    }
}
