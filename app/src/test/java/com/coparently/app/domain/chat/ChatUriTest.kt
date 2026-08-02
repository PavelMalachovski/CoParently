package com.coparently.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUriTest {

    @Test
    fun `build produces the documented uri`() {
        assertEquals("coplanly://chat", ChatUri.build())
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
}
