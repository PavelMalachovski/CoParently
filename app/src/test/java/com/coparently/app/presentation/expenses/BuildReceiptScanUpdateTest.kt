package com.coparently.app.presentation.expenses

import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptScan
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [buildReceiptScanUpdate] — the rule the whole receipt-scan feature hangs on:
 * a field the user has already touched must never be overwritten by the scan.
 *
 * Table-driven over the five pre-fillable fields (title, amount, date, category, currency),
 * each with its own untouched test: title/amount are blank-tests, date is `== today`, category
 * is `== ExpenseCategory.OTHER`, currency is `== null`.
 */
class BuildReceiptScanUpdateTest {

    private val today = LocalDate.of(2026, 7, 30)

    private val scan = ReceiptScan(
        total = 299.0,
        currency = "CZK",
        merchant = "LEKARNA DR.MAX",
        date = LocalDate.of(2026, 7, 12),
        category = ExpenseCategory.MEDICAL
    )

    /** Form state where every field is still at its untouched default. */
    private val untouchedForm = ReceiptScanFormState(
        title = "",
        amount = "",
        category = ExpenseCategory.OTHER,
        date = today,
        currency = null
    )

    // --- Untouched fields are filled in from the scan ---

    @Test
    fun `blank title is filled from the scan`() {
        val update = buildReceiptScanUpdate(scan, untouchedForm, today)
        assertEquals(scan.merchant, update.title)
    }

    @Test
    fun `blank amount is filled from the scan`() {
        val update = buildReceiptScanUpdate(scan, untouchedForm, today)
        assertEquals(scan.total.toString(), update.amount)
    }

    @Test
    fun `date still at today is filled from the scan`() {
        val update = buildReceiptScanUpdate(scan, untouchedForm, today)
        assertEquals(scan.date, update.date)
    }

    @Test
    fun `category still OTHER is filled from the scan`() {
        val update = buildReceiptScanUpdate(scan, untouchedForm, today)
        assertEquals(scan.category, update.category)
    }

    @Test
    fun `null currency is filled from the scan`() {
        val update = buildReceiptScanUpdate(scan, untouchedForm, today)
        assertEquals(SupportedCurrency.fromCode(scan.currency), update.currency)
    }

    // --- Fields the user already touched are never overwritten ---

    @Test
    fun `a non-blank title is not overwritten`() {
        val formState = untouchedForm.copy(title = "My own title")
        val update = buildReceiptScanUpdate(scan, formState, today)
        assertNull(update.title)
    }

    @Test
    fun `a non-blank amount is not overwritten`() {
        val formState = untouchedForm.copy(amount = "12.50")
        val update = buildReceiptScanUpdate(scan, formState, today)
        assertNull(update.amount)
    }

    @Test
    fun `a date moved off today is not overwritten`() {
        val formState = untouchedForm.copy(date = today.minusDays(3))
        val update = buildReceiptScanUpdate(scan, formState, today)
        assertNull(update.date)
    }

    @Test
    fun `a category moved off OTHER is not overwritten`() {
        val formState = untouchedForm.copy(category = ExpenseCategory.FOOD)
        val update = buildReceiptScanUpdate(scan, formState, today)
        assertNull(update.category)
    }

    @Test
    fun `a currency the user already picked is not overwritten`() {
        val formState = untouchedForm.copy(currency = SupportedCurrency.EUR)
        val update = buildReceiptScanUpdate(scan, formState, today)
        assertNull(update.currency)
    }
}
