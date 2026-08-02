package com.coparently.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [chatDeepLinkRoute]'s fallback: a `coplanly://chat` deep link with no conversation id
 * (a manual test push, an older payload, or a hand-typed link) must resolve to the Chat tab's
 * conversation list rather than fail or land on a blank thread, while a link that does carry
 * an id must open that specific thread rather than the list.
 */
class ChatDeepLinkRouteTest {

    @Test
    fun `a link with a conversation id opens the thread`() {
        assertEquals(
            Screen.Chat.createRoute("alice__bob"),
            chatDeepLinkRoute("alice__bob")
        )
    }

    @Test
    fun `a link with no conversation id resolves to the conversation list`() {
        assertEquals(Screen.Conversations.createRoute(), chatDeepLinkRoute(null))
    }

    @Test
    fun `a link with a blank conversation id also resolves to the conversation list`() {
        assertEquals(Screen.Conversations.createRoute(), chatDeepLinkRoute(""))
        assertEquals(Screen.Conversations.createRoute(), chatDeepLinkRoute("   "))
    }
}
