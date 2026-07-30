package com.coparently.app.domain.receipts

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for the money parsing and total detection in [ReceiptParser]. */
class ReceiptParserMoneyTest {

    @Test
    fun `parses a plain decimal with a dot`() {
        assertEquals(12.99, ReceiptParser.parseMoney("12.99"))
    }

    @Test
    fun `parses a decimal comma`() {
        assertEquals(349.5, ReceiptParser.parseMoney("349,50"))
    }

    @Test
    fun `parses a space grouped amount`() {
        assertEquals(1234.5, ReceiptParser.parseMoney("1 234,50"))
    }

    @Test
    fun `parses a dot grouped amount with a decimal comma`() {
        assertEquals(1234.5, ReceiptParser.parseMoney("1.234,50"))
    }

    @Test
    fun `treats a three digit tail as thousands grouping`() {
        assertEquals(1234.0, ReceiptParser.parseMoney("1.234"))
    }

    @Test
    fun `rejects something that is not money`() {
        assertNull(ReceiptParser.parseMoney("09.07.2026"))
        assertNull(ReceiptParser.parseMoney("abc"))
    }

    @Test
    fun `finds the total on a czech keyword line`() {
        val lines = listOf(
            "BILLA CESKA REPUBLIKA",
            "Mleko            24,90",
            "Chleb            39,90",
            "CELKEM          349,50 Kc"
        )
        assertEquals(349.5, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `ignores the VAT breakdown when looking for the total`() {
        val lines = listOf(
            "Rossmann",
            "ZAKLAD DPH 21%     999,00",
            "DPH 21%            209,79",
            "CELKEM K UHRADE    288,79"
        )
        assertEquals(288.79, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `reads the total from the line below the keyword`() {
        val lines = listOf("Lekarna", "CELKEM", "512,00 Kc")
        assertEquals(512.0, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `finds an english total`() {
        val lines = listOf("BOOKSHOP", "Item        5.00", "TOTAL      12.99")
        assertEquals(12.99, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `falls back to the largest amount when no keyword is present`() {
        val lines = listOf("Kiosk", "2,50", "18,00", "7,20")
        assertEquals(18.0, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `returns null when there is no money at all`() {
        assertNull(ReceiptParser.findTotal(listOf("hello", "world")))
    }
}
