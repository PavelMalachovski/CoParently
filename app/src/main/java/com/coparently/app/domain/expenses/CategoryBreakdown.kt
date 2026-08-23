package com.coparently.app.domain.expenses

import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory

/**
 * One category's share of a single currency's spending in a period.
 *
 * @property category The category.
 * @property amount Total spent on it, in that currency.
 * @property share Fraction of that currency's total, `0.0..1.0`. **Of its own currency's total,
 *   never of everything spent** — see [breakdownByCurrency].
 */
data class CategorySlice(
    val category: ExpenseCategory,
    val amount: Double,
    val share: Double
)

/**
 * One currency's spending in a period, broken down by category.
 *
 * @property currency ISO 4217 code these figures are in.
 * @property total Sum of every expense in that currency, and nothing else.
 * @property slices One entry per category with a non-zero total, largest amount first. A
 *   category with nothing in it is absent rather than present as a zero: a parent scanning for
 *   the big number should not read past six empty rows to find it.
 */
data class CurrencyBreakdown(
    val currency: String,
    val total: Double,
    val slices: List<CategorySlice>
)

/**
 * Breaks a period's expenses down by category, **one currency at a time**.
 *
 * This exists to be drawn as a pie, and that is exactly why the currency split is not optional.
 * The app does no FX conversion — a recorded product decision, not a gap — and
 * [calculateExpenseBalancesByCurrency] already keeps every total honest within its own currency.
 * A pie is a single total cut into slices, so a pie of two currencies is precisely the forbidden
 * thing, and worse than the plain number it replaces: a slice's angle is a *claim about
 * proportion*, and ¥1000 beside €50 makes a claim that is simply false. Nothing here can produce
 * a cross-currency figure, because there is no code path that adds two currencies together.
 *
 * [CategorySlice.share] is likewise computed against its own currency's total. Two separate
 * totals sharing one denominator would still draw a lying chart, and it is the version an
 * implementation reaches by accident.
 *
 * Shaped after [calculateExpenseBalancesByCurrency], deliberately: same grouping, same
 * largest-first ordering, same "empty input means an empty list".
 *
 * @param expenses Expenses in the period, possibly in several currencies.
 * @param paidByUid Firebase uid of the parent whose spending to show, or null for everyone.
 *   Selects on [Expense.paidBy], the same field [calculateExpenseBalance] attributes by.
 * @return One [CurrencyBreakdown] per currency with something in it, largest total first; empty
 *   when nothing matches.
 */
fun breakdownByCurrency(
    expenses: List<Expense>,
    paidByUid: String? = null
): List<CurrencyBreakdown> = expenses
    .filter { paidByUid == null || it.paidBy == paidByUid }
    .groupBy { it.currency }
    .mapNotNull { (currency, inCurrency) -> breakdownOf(currency, inCurrency) }
    .sortedByDescending { it.total }

/**
 * One currency's breakdown, or null when there is nothing to draw.
 *
 * Null rather than a zero-total entry for two reasons, both of which show up on screen rather
 * than in a log. A total of zero is a division by zero: every share becomes `NaN`, and a `NaN`
 * sweep angle draws no arc at all, so the chart would come back silently blank instead of
 * saying it has nothing. And the screen picks its default currency chip from this list, so a
 * currency with nothing in it would offer a chip that selects an empty chart.
 *
 * A month can legitimately total zero — a refund cancelling a purchase, or a mis-typed 0.
 */
private fun breakdownOf(currency: String, inCurrency: List<Expense>): CurrencyBreakdown? {
    val total = inCurrency.sumOf { it.amount }
    if (total <= 0.0) return null

    val slices = inCurrency
        .groupBy { it.category }
        .map { (category, ofCategory) -> category to ofCategory.sumOf { it.amount } }
        // A category can net out to zero or below — a refund filed against it, or a correcting
        // negative entry. A pie cannot draw a negative slice, and a zero one is a row with
        // nothing in it, so neither becomes a slice. `share` is then always within 0..1.
        .filter { (_, amount) -> amount > 0.0 }
        .map { (category, amount) -> CategorySlice(category, amount, (amount / total).coerceAtMost(1.0)) }
        .sortedByDescending { it.amount }

    return if (slices.isEmpty()) null else CurrencyBreakdown(currency, total, slices)
}
