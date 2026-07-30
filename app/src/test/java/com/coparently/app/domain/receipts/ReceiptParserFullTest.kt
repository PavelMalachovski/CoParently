package com.coparently.app.domain.receipts

import com.coparently.app.domain.model.ExpenseCategory
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end tests for [ReceiptParser.parse] over realistic receipt layouts. */
class ReceiptParserFullTest {

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun `reads a czech pharmacy receipt`() {
        val lines = listOf(
            "LEKARNA DR.MAX",
            "Namesti Miru 12, Praha 2",
            "ICO 12345678",
            "Paralen 500mg        89,00",
            "Vitamin D            210,00",
            "CELKEM K UHRADE      299,00 Kc",
            "Datum: 12.07.2026 16:04"
        )

        val scan = ReceiptParser.parse(lines, today)

        assertEquals(299.0, scan.total)
        assertEquals("CZK", scan.currency)
        assertEquals("LEKARNA DR.MAX", scan.merchant)
        assertEquals(LocalDate.of(2026, 7, 12), scan.date)
        assertEquals(ExpenseCategory.MEDICAL, scan.category)
    }

    @Test
    fun `reads an english receipt in euro`() {
        val lines = listOf(
            "CITY BOOKSHOP",
            "Notebook          4.50",
            "Pencils           3.49",
            "TOTAL            12.99 EUR",
            "2026-05-21"
        )

        val scan = ReceiptParser.parse(lines, today)

        assertEquals(12.99, scan.total)
        assertEquals("EUR", scan.currency)
        assertEquals("CITY BOOKSHOP", scan.merchant)
        assertEquals(LocalDate.of(2026, 5, 21), scan.date)
    }

    @Test
    fun `skips address and registration lines when naming the merchant`() {
        val lines = listOf(
            "SKOLNI JIDELNA",
            "Ulice Dlouha 5",
            "DIC CZ12345678",
            "tel. 777 123 456",
            "CELKEM 640,00 Kc"
        )

        assertEquals("SKOLNI JIDELNA", ReceiptParser.findMerchant(lines))
    }

    @Test
    fun `does not mistake tel inside hotel for the phone-number stopword`() {
        val lines = listOf("HOTEL PRAHA", "Nocleh 1200,00")
        assertEquals("HOTEL PRAHA", ReceiptParser.findMerchant(lines))
    }

    @Test
    fun `guesses education from school keywords`() {
        assertEquals(
            ExpenseCategory.EDUCATION,
            ReceiptParser.findCategory(listOf("MATERSKA SKOLKA", "Skolne za cerven"))
        )
    }

    @Test
    fun `guesses transportation from a fuel receipt`() {
        assertEquals(
            ExpenseCategory.TRANSPORTATION,
            ReceiptParser.findCategory(listOf("ORLEN", "Benzin Natural 95"))
        )
    }

    @Test
    fun `leaves the category unset when nothing matches`() {
        assertNull(ReceiptParser.findCategory(listOf("NEJAKY OBCHOD", "Zbozi 100,00")))
    }

    @Test
    fun `returns an empty scan for unreadable input`() {
        val scan = ReceiptParser.parse(listOf("~~~", "###"), today)

        assertTrue(scan.isEmpty)
        assertNull(scan.total)
        assertNull(scan.merchant)
    }
}
