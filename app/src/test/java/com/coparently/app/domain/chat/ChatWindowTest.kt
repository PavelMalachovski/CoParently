package com.coparently.app.domain.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat's message window (CQ-6).
 *
 * `MessageDao.getMessages` was unbounded, so opening Chat materialised every message a pair had
 * ever exchanged. Bounding it is only safe if the reader can still reach the history — showing
 * the tail and saying nothing is the CQ-7 defect in a different collection — so what these tests
 * are really about is the button never disappearing while there is still something behind it.
 */
class ChatWindowTest {

    @Test
    fun `a full window means there may be more`() {
        assertTrue(ChatWindow.hasMore(loaded = ChatWindow.INITIAL, limit = ChatWindow.INITIAL))
    }

    @Test
    fun `a short window means the whole thread is on screen`() {
        assertFalse(ChatWindow.hasMore(loaded = ChatWindow.INITIAL - 1, limit = ChatWindow.INITIAL))
        assertFalse(ChatWindow.hasMore(loaded = 0, limit = ChatWindow.INITIAL))
    }

    @Test
    fun `more than asked for still means more`() {
        // Room cannot return more than the limit, but the comparison is `>=` rather than `==`
        // so that a future caller passing a stale limit errs toward offering the button. Hiding
        // history is the failure worth avoiding; an extra button is not.
        assertTrue(ChatWindow.hasMore(loaded = ChatWindow.INITIAL + 1, limit = ChatWindow.INITIAL))
    }

    @Test
    fun `the window grows by a screenful and never shrinks`() {
        var window = ChatWindow.INITIAL
        repeat(4) { window = ChatWindow.grow(window) }

        assertEquals(ChatWindow.INITIAL + 4 * ChatWindow.STEP, window)
        assertTrue(ChatWindow.grow(window) > window, "growing must never reduce the window")
    }

    @Test
    fun `growing is monotonic from any starting point`() {
        // It grows, it does not page: the messages already on screen must still be there after,
        // or a reader loses their place every time they ask for history.
        listOf(0, 1, ChatWindow.INITIAL, 10_000).forEach { start ->
            assertTrue(ChatWindow.grow(start) > start, "grow($start) did not grow")
        }
    }

    @Test
    fun `the render window is smaller than the listener's own`() {
        // 200 is what `FirestoreMessageDataSource` keeps live via `limitToLast`. The two are
        // separate decisions — what the device receives versus what one screen renders — and this
        // asserts they have not been quietly tied together.
        assertTrue(ChatWindow.INITIAL < 200, "the render window should stay under the mirror's")
    }
}
