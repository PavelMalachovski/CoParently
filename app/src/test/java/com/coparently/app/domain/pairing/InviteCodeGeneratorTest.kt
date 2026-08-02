package com.coparently.app.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InviteCodeGeneratorTest {

    @Test
    fun `generated code has the required length`() {
        repeat(200) {
            assertEquals(InviteCodeGenerator.LENGTH, InviteCodeGenerator.generate().length)
        }
    }

    @Test
    fun `generated code never contains visually ambiguous characters`() {
        val forbidden = setOf('O', '0', 'I', '1', 'L')
        repeat(2_000) {
            val code = InviteCodeGenerator.generate()
            assertTrue(
                "code $code contains a forbidden character",
                code.none { it in forbidden }
            )
        }
    }

    @Test
    fun `generated code uses only the published alphabet`() {
        repeat(2_000) {
            val code = InviteCodeGenerator.generate()
            assertTrue(code.all { it in InviteCodeGenerator.ALPHABET })
        }
    }

    @Test
    fun `generation is deterministic for a seeded random`() {
        assertEquals(
            InviteCodeGenerator.generate(Random(42)),
            InviteCodeGenerator.generate(Random(42))
        )
    }

    @Test
    fun `isValid accepts a well formed code and rejects everything else`() {
        assertTrue(InviteCodeGenerator.isValid("4F7K2M"))
        assertFalse("wrong length", InviteCodeGenerator.isValid("4F7K2"))
        assertFalse("lowercase", InviteCodeGenerator.isValid("4f7k2m"))
        assertFalse("ambiguous char", InviteCodeGenerator.isValid("4F7K2O"))
        assertFalse("empty", InviteCodeGenerator.isValid(""))
    }
}
