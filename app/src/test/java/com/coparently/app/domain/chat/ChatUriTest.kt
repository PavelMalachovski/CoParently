package com.coparently.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUriTest {

    @Test
    fun `build with no id produces the bare chat link`() {
        assertEquals("coplanly://chat", ChatUri.build())
    }

    @Test
    fun `build with a blank id produces the bare chat link`() {
        assertEquals("coplanly://chat", ChatUri.build(""))
    }

    @Test
    fun `build with an id carries it as a query parameter`() {
        assertEquals("coplanly://chat?conversationId=alice__bob", ChatUri.build("alice__bob"))
    }

    @Test
    fun `isChatUri accepts the documented scheme and host`() {
        assertTrue(ChatUri.isChatUri("coplanly", "chat"))
    }

    @Test
    fun `isChatUri rejects a wrong scheme`() {
        assertFalse(ChatUri.isChatUri("https", "chat"))
    }

    @Test
    fun `isChatUri rejects a wrong host`() {
        assertFalse(ChatUri.isChatUri("coplanly", "pair"))
    }

    @Test
    fun `isChatUri rejects null scheme or host`() {
        assertFalse(ChatUri.isChatUri(null, "chat"))
        assertFalse(ChatUri.isChatUri("coplanly", null))
        assertFalse(ChatUri.isChatUri(null, null))
    }

    @Test
    fun `extractConversationId reads the id out of a full uri`() {
        assertEquals(
            "alice__bob",
            ChatUri.extractConversationId("coplanly://chat?conversationId=alice__bob")
        )
    }

    @Test
    fun `extractConversationId returns null for a bare chat link`() {
        // The id-less link CoPlanlyMessagingService already sent before this change - must
        // still resolve to "open the list", not fail.
        assertNull(ChatUri.extractConversationId("coplanly://chat"))
    }

    @Test
    fun `extractConversationId returns null when the parameter is blank`() {
        assertNull(ChatUri.extractConversationId("coplanly://chat?conversationId="))
    }

    @Test
    fun `extractConversationId returns null for unrelated text`() {
        assertNull(ChatUri.extractConversationId("hello"))
        assertNull(ChatUri.extractConversationId(""))
    }

    @Test
    fun `build and extractConversationId round-trip`() {
        val link = ChatUri.build("alice__bob")
        assertEquals("alice__bob", ChatUri.extractConversationId(link))
    }
}
