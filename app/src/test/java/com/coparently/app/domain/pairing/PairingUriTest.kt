package com.coparently.app.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUriTest {

    @Test
    fun `build produces the documented uri`() {
        assertEquals("coplanly://pair?code=4F7K2M", PairingUri.build("4F7K2M"))
    }

    @Test
    fun `extractCode reads a bare code`() {
        assertEquals("4F7K2M", PairingUri.extractCode("4F7K2M"))
    }

    @Test
    fun `extractCode trims and uppercases a bare code`() {
        assertEquals("4F7K2M", PairingUri.extractCode("  4f7k2m "))
    }

    @Test
    fun `extractCode reads a full uri`() {
        assertEquals("4F7K2M", PairingUri.extractCode("coplanly://pair?code=4F7K2M"))
    }

    @Test
    fun `extractCode reads a code out of pasted share text`() {
        val shared = "Pavel invites you to CoPlanly. Code: 4F7K2M · coplanly://pair?code=4F7K2M"
        assertEquals("4F7K2M", PairingUri.extractCode(shared))
    }

    @Test
    fun `extractCode rejects an invalid code`() {
        assertNull(PairingUri.extractCode("coplanly://pair?code=4F7K2O"))
        assertNull(PairingUri.extractCode("hello"))
        assertNull(PairingUri.extractCode(""))
    }
}
