package com.coparently.app.domain.custody

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CustodyKeyTest {

    @Test
    fun `the same pair yields the same id in either order`() {
        assertEquals(CustodyKey.of("aaa", "bbb"), CustodyKey.of("bbb", "aaa"))
    }

    @Test
    fun `the id is the two uids sorted and joined`() {
        assertEquals("aaa__bbb", CustodyKey.of("bbb", "aaa"))
    }

    @Test
    fun `different pairs yield different ids`() {
        assert(CustodyKey.of("aaa", "bbb") != CustodyKey.of("aaa", "ccc"))
    }

    @Test
    fun `a blank uid is refused`() {
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("", "bbb") }
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("aaa", "  ") }
    }

    @Test
    fun `a user has no custody arrangement with themselves`() {
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("aaa", "aaa") }
    }

    @Test
    fun `a uid containing the separator is refused`() {
        // Without this, of("x__y", "z") and of("x", "y__z") both join to "x__y__z":
        // two different pairs colliding on one document.
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("x__y", "z") }
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("x", "y__z") }
    }
}
