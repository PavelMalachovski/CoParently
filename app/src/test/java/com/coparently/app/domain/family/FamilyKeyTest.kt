package com.coparently.app.domain.family

import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.custody.CustodyKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The id of one co-parenting relationship.
 *
 * The first case is the one worth having: the custody schedule, the agreed split and the chat
 * thread are all named with this key, and the whole plan for more than one partner rests on their
 * being the *same* key. It was true by coincidence of two identical implementations; it is now
 * true by construction, and this is what would notice if that changed.
 */
class FamilyKeyTest {

    @Test
    fun `the custody document and the chat thread are named with the same key`() {
        val family = FamilyKey.of("uid-b", "uid-a")

        assertEquals(family, CustodyKey.of("uid-b", "uid-a"))
        assertEquals(family, ConversationKey.of("uid-b", "uid-a"))
    }

    @Test
    fun `the order the two uids arrive in does not matter`() {
        // Neither device knows which of them will ask first, and both must land on one document.
        assertEquals(FamilyKey.of("uid-a", "uid-b"), FamilyKey.of("uid-b", "uid-a"))
    }

    @Test
    fun `the separator is part of the stored schema`() {
        // Every custody model, family settings document and conversation in production is named
        // with it. Changing it renames nothing and unlinks everything.
        assertEquals("__", FamilyKey.SEPARATOR)
        assertEquals("uid-a__uid-b", FamilyKey.of("uid-a", "uid-b"))
    }

    @Test
    fun `two different pairs can never collide on one id`() {
        // Without the separator check, of("x__y", "z") and of("x", "y__z") both join to
        // "x__y__z" — two unrelated pairs sharing one document, which is the failure this
        // function exists to prevent.
        assertFailsWith<IllegalArgumentException> { FamilyKey.of("x__y", "z") }
        assertFailsWith<IllegalArgumentException> { FamilyKey.of("x", "y__z") }
    }

    @Test
    fun `a uid that names nobody is a caller bug, not an id`() {
        assertFailsWith<IllegalArgumentException> { FamilyKey.of("", "uid-b") }
        assertFailsWith<IllegalArgumentException> { FamilyKey.of("uid-a", "   ") }
    }

    @Test
    fun `nobody co-parents with themselves`() {
        assertFailsWith<IllegalArgumentException> { FamilyKey.of("uid-a", "uid-a") }
    }

    @Test
    fun `the two members can be read back out of the id`() {
        // `custody_models`' own read rule already splits the id this way, to check the caller is
        // one of the two named uids on a document that does not exist yet.
        assertEquals("uid-a" to "uid-b", FamilyKey.membersOf(FamilyKey.of("uid-b", "uid-a")))
    }

    @Test
    fun `text that is not a family id reads back as nothing`() {
        assertNull(FamilyKey.membersOf("uid-a"))
        assertNull(FamilyKey.membersOf("a__b__c"))
        assertNull(FamilyKey.membersOf("__uid-b"))
        assertNull(FamilyKey.membersOf(""))
    }
}
