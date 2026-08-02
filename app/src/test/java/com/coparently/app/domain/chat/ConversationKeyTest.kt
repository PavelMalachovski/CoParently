package com.coparently.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationKeyTest {

    @Test
    fun `both argument orders produce the same id`() {
        assertEquals(
            ConversationKey.of("uidA", "uidB"),
            ConversationKey.of("uidB", "uidA")
        )
    }

    @Test
    fun `the id is stable across calls`() {
        assertEquals(ConversationKey.of("uidA", "uidB"), ConversationKey.of("uidA", "uidB"))
    }

    @Test
    fun `different pairs do not collide`() {
        assertNotEquals(ConversationKey.of("uidA", "uidB"), ConversationKey.of("uidA", "uidC"))
    }

    @Test
    fun `a pair sharing a prefix does not collide with another`() {
        // "ab" + "c" and "a" + "bc" must not both become "abc".
        assertNotEquals(ConversationKey.of("ab", "c"), ConversationKey.of("a", "bc"))
    }

    @Test
    fun `pairing a user with themselves is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationKey.of("uidA", "uidA")
        }
    }

    @Test
    fun `a blank uid is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationKey.of("uidA", "  ")
        }
    }
}
