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
 * Works out the split bar and settle-up figure for a month of expenses.
 *
 * The money screen of a two-household app never said who paid; this is what makes "Mom paid
 * $154.10 / Dad paid $94.40 / Dad owes you $29.85" possible without adding any storage.
 *
 * Each expense leaves its payer out of pocket by the full amount, and every uid in
 * `splitBetween` owes an equal share of it. An expense with an empty `splitBetween` is treated
 * as **unsplit**: it counts towards the payer's total and towards the month total, but creates
 * no debt in either direction — a parent buying something purely for themselves is not a claim
 * on the other.
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
            currentUserOwes += expense.amount / expense.splitBetween.size
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
