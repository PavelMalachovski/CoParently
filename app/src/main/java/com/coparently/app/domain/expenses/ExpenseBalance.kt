package com.coparently.app.domain.expenses

import com.coparently.app.domain.model.Expense

/**
 * Who paid what over a set of expenses, and who therefore owes whom.
 *
 * @property momPaid Total paid out of pocket by the mom
 * @property dadPaid Total paid out of pocket by the dad
 * @property total Sum of every expense considered
 * @property netForCurrentUser Positive when the co-parent owes the current user, negative when
 *   the current user owes the co-parent, zero when settled
 * @property splitKnown Whether both parents could be identified; false while unpaired, in which
 *   case the split and balance are not meaningful and should not be shown
 */
data class ExpenseBalance(
    val momPaid: Double,
    val dadPaid: Double,
    val total: Double,
    val netForCurrentUser: Double,
    val splitKnown: Boolean
) {
    /** Fraction of the total paid by the mom, used to size the split bar. 0.5 when nothing spent. */
    val momShareOfPaid: Float
        get() = if (total <= 0.0) 0.5f else (momPaid / total).toFloat()
}

/**
 * A single currency present in a period, paired with the [ExpenseBalance] computed from only the
 * expenses in that currency.
 *
 * @property currency ISO 4217 code these figures are in
 * @property balance Paid/owed figures for that currency's expenses alone
 */
data class CurrencyBalance(
    val currency: String,
    val balance: ExpenseBalance
)

/**
 * Works out the split bar and settle-up figure for a month of expenses.
 *
 * The money screen of a two-household app never said who paid; this is what makes
 * "Olya: $154.10 / Pavel: $94.40 / Pavel owes you $29.85" possible without adding any storage.
 * The figures are per *slot* — `momPaid` is slot 1, `dadPaid` slot 2 — and the screen turns a
 * slot into a person's name; see `presentation/common/ParentLabels.kt`.
 *
 * Each expense leaves its payer out of pocket by the full amount, and every uid in
 * `splitBetween` owes **their agreed share** of it. An expense with an empty `splitBetween` is
 * treated as **unsplit**: it counts towards the payer's total and towards the month total, but
 * creates no debt in either direction — a parent buying something purely for themselves is not a
 * claim on the other.
 *
 * **The share comes from the expense, not from the family's current agreement.** Each expense
 * carries the ratio it was recorded under (`Expense.splitBasisPoints`), so renegotiating the
 * split cannot re-price a month the two parents have already settled and argued about. A row
 * from before the agreement existed carries null and is divided evenly, which is what it was.
 *
 * @param expenses Expenses in the period
 * @param currentUserId Firebase uid of the signed-in parent
 * @param roleByUid Map of uid to `"mom"`/`"dad"`; incomplete while unpaired
 * @return The computed balance; [ExpenseBalance.splitKnown] is false when the two parents
 *   cannot both be identified
 */
fun calculateExpenseBalance(
    expenses: List<Expense>,
    currentUserId: String,
    roleByUid: Map<String, String>
): ExpenseBalance {
    var momPaid = 0.0
    var dadPaid = 0.0
    var total = 0.0
    var currentUserPaid = 0.0
    var currentUserOwes = 0.0

    expenses.forEach { expense ->
        total += expense.amount
        when (roleByUid[expense.paidBy]) {
            "mom" -> momPaid += expense.amount
            "dad" -> dadPaid += expense.amount
            else -> Unit
        }
        if (expense.paidBy == currentUserId) {
            currentUserPaid += expense.amount
        }
        if (expense.splitBetween.isNotEmpty() && currentUserId in expense.splitBetween) {
            currentUserOwes += expense.amount * shareOf(expense, currentUserId, roleByUid)
        }
    }

    // Both roles must be known for the split bar to mean anything: while unpaired there is only
    // one parent on record, and a 100% bar would be decoration pretending to be data.
    val splitKnown = roleByUid.values.toSet().containsAll(setOf("mom", "dad"))

    return ExpenseBalance(
        momPaid = momPaid,
        dadPaid = dadPaid,
        total = total,
        netForCurrentUser = currentUserPaid - currentUserOwes,
        splitKnown = splitKnown
    )
}

/**
 * The fraction of [expense] that [uid] owes.
 *
 * The ratio stored on the expense when both parents' slots are known and it carries one; an even
 * division among the people it is split between otherwise. The fallback covers three real cases
 * and gets each of them right: a row recorded before the agreement existed, an unpaired account
 * where one of the two slots is unknown, and a pair whose two parents both still read `"mom"` —
 * the same condition that leaves `ExpenseBalance.splitKnown` false, because the two people
 * genuinely cannot be told apart yet.
 */
private fun shareOf(expense: Expense, uid: String, roleByUid: Map<String, String>): Double {
    val ratio = SplitRatio.fromStored(expense.splitBasisPoints)
    val slot = roleByUid[uid]
    val slotsKnown = roleByUid.values.toSet().containsAll(setOf("mom", "dad"))
    return if (ratio != null && slot != null && slotsKnown) {
        ratio.shareFor(slot)
    } else {
        1.0 / expense.splitBetween.size
    }
}

/**
 * Splits [expenses] by currency and computes a separate [ExpenseBalance] for each.
 *
 * A month mixing, say, CZK and USD has no meaningful single total — the app does no FX
 * conversion (spec §10) — so summing the raw amounts would show a wrong, currency-blind figure
 * (e.g. "749 + 15 = 764"). Reporting one balance per currency keeps every total, split and
 * settle-up figure honest within its own currency.
 *
 * @param expenses Expenses in the period, possibly in several currencies
 * @param currentUserId Firebase uid of the signed-in parent
 * @param roleByUid Map of uid to `"mom"`/`"dad"`; incomplete while unpaired
 * @return One [CurrencyBalance] per currency present, largest total first; empty when there are
 *   no expenses
 */
fun calculateExpenseBalancesByCurrency(
    expenses: List<Expense>,
    currentUserId: String,
    roleByUid: Map<String, String>
): List<CurrencyBalance> =
    expenses.groupBy { it.currency }
        .map { (currency, inCurrency) ->
            CurrencyBalance(currency, calculateExpenseBalance(inCurrency, currentUserId, roleByUid))
        }
        .sortedByDescending { it.balance.total }
