package com.coparently.app.domain.expenses

import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic a pie chart makes a visual claim about.
 *
 * A slice's angle *is* a claim about proportion, so the two things that must not be wrong are
 * which expenses went into a total and whether two currencies were ever added together. The
 * second is not a bug this app would notice at run time — it would simply draw a confident,
 * wrong chart.
 */
class CategoryBreakdownTest {

    @Test
    fun `two currencies produce two independent breakdowns and never a combined total`() {
        val result = breakdownByCurrency(
            listOf(
                expense(amount = 100.0, currency = "EUR", category = ExpenseCategory.CLOTHING),
                expense(amount = 1000.0, currency = "CZK", category = ExpenseCategory.CLOTHING)
            )
        )

        assertEquals(2, result.size)
        assertEquals(setOf("EUR", "CZK"), result.map { it.currency }.toSet())
        // The forbidden thing: no entry may total across currencies.
        assertTrue(result.none { it.total == 1100.0 })
        assertEquals(1000.0, result.first { it.currency == "CZK" }.total, EPSILON)
        assertEquals(100.0, result.first { it.currency == "EUR" }.total, EPSILON)
    }

    @Test
    fun `a currency's shares are of its own total, not of everything spent`() {
        // The subtler half of the rule above. Two separate totals but one shared denominator
        // would still draw a chart that lies, and it is the version an implementation reaches
        // by accident.
        val result = breakdownByCurrency(
            listOf(
                expense(amount = 100.0, currency = "EUR", category = ExpenseCategory.CLOTHING),
                expense(amount = 9000.0, currency = "CZK", category = ExpenseCategory.FOOD)
            )
        )

        assertEquals(1.0, result.first { it.currency == "EUR" }.slices.single().share, EPSILON)
        assertEquals(1.0, result.first { it.currency == "CZK" }.slices.single().share, EPSILON)
    }

    @Test
    fun `slices are sorted with the largest first`() {
        // What is eating the money should be the first row; item 14 asks for that explicitly.
        val slices = breakdownByCurrency(
            listOf(
                expense(amount = 10.0, category = ExpenseCategory.TOYS),
                expense(amount = 90.0, category = ExpenseCategory.EDUCATION),
                expense(amount = 50.0, category = ExpenseCategory.FOOD)
            )
        ).single().slices

        assertEquals(
            listOf(ExpenseCategory.EDUCATION, ExpenseCategory.FOOD, ExpenseCategory.TOYS),
            slices.map { it.category }
        )
    }

    @Test
    fun `a category with nothing in it is absent, not a zero row`() {
        // A parent scanning for the big number should not read past six empty lines to find it.
        val slices = breakdownByCurrency(
            listOf(expense(amount = 10.0, category = ExpenseCategory.TOYS))
        ).single().slices

        assertEquals(listOf(ExpenseCategory.TOYS), slices.map { it.category })
    }

    @Test
    fun `several expenses in one category are one slice`() {
        val slices = breakdownByCurrency(
            listOf(
                expense(amount = 10.0, category = ExpenseCategory.FOOD),
                expense(amount = 15.5, category = ExpenseCategory.FOOD)
            )
        ).single().slices

        assertEquals(1, slices.size)
        assertEquals(25.5, slices.single().amount, EPSILON)
    }

    @Test
    fun `shares sum to one within each currency independently`() {
        val result = breakdownByCurrency(
            listOf(
                expense(amount = 30.0, currency = "EUR", category = ExpenseCategory.FOOD),
                expense(amount = 70.0, currency = "EUR", category = ExpenseCategory.MEDICAL),
                expense(amount = 1.0, currency = "CZK", category = ExpenseCategory.TOYS),
                expense(amount = 2.0, currency = "CZK", category = ExpenseCategory.HOUSEHOLD)
            )
        )

        result.forEach { breakdown ->
            assertEquals(1.0, breakdown.slices.sumOf { it.share }, EPSILON)
        }
    }

    @Test
    fun `filtering by a payer selects on paidBy`() {
        val slices = breakdownByCurrency(
            listOf(
                expense(amount = 40.0, category = ExpenseCategory.FOOD, paidBy = "uid-a"),
                expense(amount = 60.0, category = ExpenseCategory.MEDICAL, paidBy = "uid-b")
            ),
            paidByUid = "uid-a"
        ).single()

        assertEquals(40.0, slices.total, EPSILON)
        assertEquals(listOf(ExpenseCategory.FOOD), slices.slices.map { it.category })
    }

    @Test
    fun `everyone matches the unfiltered totals exactly`() {
        val expenses = listOf(
            expense(amount = 40.0, category = ExpenseCategory.FOOD, paidBy = "uid-a"),
            expense(amount = 60.0, category = ExpenseCategory.MEDICAL, paidBy = "uid-b")
        )

        assertEquals(breakdownByCurrency(expenses), breakdownByCurrency(expenses, paidByUid = null))
    }

    @Test
    fun `filtering to a payer with nothing this month produces no breakdown at all`() {
        // Not a zero-total entry: the chart would then be asked to draw a circle of nothing,
        // and the screen would show a currency chip for a currency the filter excluded.
        val result = breakdownByCurrency(
            listOf(expense(amount = 40.0, category = ExpenseCategory.FOOD, paidBy = "uid-a")),
            paidByUid = "uid-b"
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun `an empty month produces an empty list rather than a zero-total entry`() {
        assertEquals(emptyList(), breakdownByCurrency(emptyList()))
    }

    @Test
    fun `currencies are ordered with the largest total first`() {
        // The screen defaults its currency chip to one of these, and "the currency with the
        // most expenses" is only well defined if the order is.
        val result = breakdownByCurrency(
            listOf(
                expense(amount = 5.0, currency = "EUR", category = ExpenseCategory.FOOD),
                expense(amount = 9000.0, currency = "CZK", category = ExpenseCategory.FOOD)
            )
        )

        assertEquals(listOf("CZK", "EUR"), result.map { it.currency })
    }

    @Test
    fun `a currency whose expenses all total zero is dropped rather than divided by`() {
        // A refund pair, or a mis-entered zero. Dividing by it is a NaN share on every slice,
        // and a NaN sweep angle silently draws nothing at all.
        val result = breakdownByCurrency(
            listOf(expense(amount = 0.0, currency = "EUR", category = ExpenseCategory.FOOD))
        )

        assertEquals(emptyList(), result)
        assertTrue(result.flatMap { it.slices }.none { it.share.isNaN() })
    }

    @Test
    fun `a negative amount cannot make a share exceed one or go below zero`() {
        // Nothing in the app stops a parent typing a minus sign. A pie cannot draw a negative
        // slice, so the arithmetic must not produce one for the chart to try.
        val breakdown = breakdownByCurrency(
            listOf(
                expense(amount = 100.0, category = ExpenseCategory.FOOD),
                expense(amount = -30.0, category = ExpenseCategory.TOYS)
            )
        ).single()

        breakdown.slices.forEach { slice ->
            assertTrue(slice.share in 0.0..1.0, "share ${slice.share} is outside 0..1")
        }
    }

    private fun expense(
        amount: Double,
        category: ExpenseCategory,
        currency: String = "USD",
        paidBy: String = "uid-a"
    ) = Expense(
        id = "e-${abs(amount.hashCode())}-$category-$currency-$paidBy",
        title = category.name,
        amount = amount,
        currency = currency,
        category = category,
        paidBy = paidBy,
        date = LocalDate.of(2026, 8, 21)
    )

    private companion object {
        const val EPSILON = 1e-9
    }
}
