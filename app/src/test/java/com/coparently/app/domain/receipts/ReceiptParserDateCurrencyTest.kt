package com.coparently.app.domain.receipts

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for currency and purchase-date detection in [ReceiptParser]. */
class ReceiptParserDateCurrencyTest {

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun `detects czech crowns`() {
        assertEquals("CZK", ReceiptParser.findCurrency(listOf("CELKEM 349,50 Kč")))
    }

    @Test
    fun `detects crowns written without diacritics by the recognizer`() {
        assertEquals("CZK", ReceiptParser.findCurrency(listOf("CELKEM 349,50 Kc")))
    }

    @Test
    fun `detects euro from the symbol`() {
        assertEquals("EUR", ReceiptParser.findCurrency(listOf("TOTAL 12,99 €")))
    }

    @Test
    fun `detects zloty`() {
        assertEquals("PLN", ReceiptParser.findCurrency(listOf("SUMA 45,00 zł")))
    }

    @Test
    fun `returns null when no currency is printed`() {
        assertNull(ReceiptParser.findCurrency(listOf("CELKEM 349,50")))
    }

    @Test
    fun `reads a czech style date`() {
        assertEquals(
            LocalDate.of(2026, 7, 12),
            ReceiptParser.findDate(listOf("Datum: 12.07.2026 14:32"), today)
        )
    }

    @Test
    fun `reads a slash separated date`() {
        assertEquals(
            LocalDate.of(2026, 6, 3),
            ReceiptParser.findDate(listOf("03/06/2026"), today)
        )
    }

    @Test
    fun `reads an iso date`() {
        assertEquals(
            LocalDate.of(2026, 5, 21),
            ReceiptParser.findDate(listOf("2026-05-21"), today)
        )
    }

    @Test
    fun `reads a two digit year`() {
        assertEquals(
            LocalDate.of(2026, 7, 12),
            ReceiptParser.findDate(listOf("12.07.26"), today)
        )
    }

    @Test
    fun `rejects a future date such as a card expiry`() {
        assertNull(ReceiptParser.findDate(listOf("Platnost do 01.01.2030"), today))
    }

    @Test
    fun `rejects a date older than two years`() {
        assertNull(ReceiptParser.findDate(listOf("01.01.2020"), today))
    }

    @Test
    fun `skips a card expiry and takes the real purchase date`() {
        val lines = listOf("VISA exp 09/2031", "Datum 12.07.2026")
        assertEquals(LocalDate.of(2026, 7, 12), ReceiptParser.findDate(lines, today))
    }

    @Test
    fun `rejects an impossible date`() {
        assertNull(ReceiptParser.findDate(listOf("32.13.2026"), today))
    }
}
