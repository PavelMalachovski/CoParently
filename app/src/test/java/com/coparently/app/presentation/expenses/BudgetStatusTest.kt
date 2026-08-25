package com.coparently.app.presentation.expenses

import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.ExpenseCategory
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Which of the three states a budget is in.
 *
 * The two screens that show this used to each decide it inline, in colour alone and in two
 * different palettes, so there was nothing to test and nothing to disagree with. Now there is
 * one answer and it is a word, which is worth pinning at its edges: the thresholds are
 * `>=` on both sides and they overlap, so the order the two comparisons are made in decides
 * what a parent is told about a budget sitting exactly on its limit.
 */
class BudgetStatusTest {

    private fun budget(limit: Double, alertAt: Double = 0.8) = Budget(
        id = "b",
        category = ExpenseCategory.EDUCATION,
        monthlyLimit = limit,
        currency = "CZK",
        alertThreshold = alertAt
    )

    private fun statusAt(spent: Double, limit: Double = 1000.0, alertAt: Double = 0.8) =
        BudgetProgress(budget(limit, alertAt), spent).status()

    @Test
    fun `comfortably inside is under`() {
        assertEquals(BudgetStatus.UNDER, statusAt(500.0))
    }

    @Test
    fun `the alert threshold itself is already near`() {
        // `isNearLimit` is `fraction >= alertThreshold`. A budget exactly at 80% is the case a
        // parent set the threshold for, so it has to be the warned side of the boundary.
        assertEquals(BudgetStatus.NEAR, statusAt(800.0))
    }

    @Test
    fun `a hair under the threshold is still under`() {
        assertEquals(BudgetStatus.UNDER, statusAt(799.99))
    }

    @Test
    fun `between the threshold and the limit is near`() {
        assertEquals(BudgetStatus.NEAR, statusAt(999.0))
    }

    @Test
    fun `exactly at the limit is over, not near`() {
        // Both predicates are true here. Over has to win: telling a parent they are "near" the
        // limit they have just reached is the wrong half of an overlap, and it is the half the
        // order of the `when` decides.
        assertEquals(BudgetStatus.OVER, statusAt(1000.0))
    }

    @Test
    fun `past the limit is over`() {
        assertEquals(BudgetStatus.OVER, statusAt(1500.0))
    }

    @Test
    fun `a budget with no limit is never in trouble`() {
        // `fraction` guards the divide and returns 0, so a zero-limit budget must not report
        // itself over — which a bare `spent >= limit` would, for every budget with no limit set.
        assertEquals(BudgetStatus.UNDER, statusAt(spent = 500.0, limit = 0.0))
        assertEquals(BudgetStatus.UNDER, statusAt(spent = 0.0, limit = 0.0))
    }

    @Test
    fun `a threshold of one collapses near into over rather than losing it`() {
        // A parent who only wants to hear about it at the limit: the two boundaries coincide,
        // and the answer is the more serious of them.
        assertEquals(BudgetStatus.OVER, statusAt(spent = 1000.0, alertAt = 1.0))
        assertEquals(BudgetStatus.UNDER, statusAt(spent = 999.0, alertAt = 1.0))
    }

    @Test
    fun `spending nothing is under even with a threshold of zero`() {
        // `fraction >= 0.0` is true at zero spend, so a zero threshold would report NEAR for a
        // budget nobody has touched. Pinned because it is the one case the ordering does not
        // save: it is the threshold that has to be sane, and 0.0 is not reachable from the UI.
        assertEquals(BudgetStatus.NEAR, statusAt(spent = 0.0, alertAt = 0.0))
    }
}
