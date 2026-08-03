// The file is named for the chips it renders; BudgetProgress is their view model, not the subject.
@file:Suppress("MatchingDeclarationName")

package com.coparently.app.presentation.expenses

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.Expense
import com.coparently.app.presentation.common.PillChip

/**
 * How far past a budget's own alert threshold spending has gone.
 *
 * @property budget The budget
 * @property spent Amount spent in that budget's category and currency this month
 */
data class BudgetProgress(val budget: Budget, val spent: Double) {

    /** Fraction of the limit used; 0 when the limit is zero, so a chip never divides by it. */
    val fraction: Double
        get() = if (budget.monthlyLimit <= 0.0) 0.0 else spent / budget.monthlyLimit

    /** True once spending has passed the budget's own alert threshold (default 80%). */
    val isNearLimit: Boolean
        get() = fraction >= budget.alertThreshold

    /** True once spending has passed the limit entirely. */
    val isOverLimit: Boolean
        get() = fraction >= 1.0
}

/**
 * Works out this month's progress against each active budget.
 *
 * Spend is matched on both category **and** currency: the app does no FX conversion, so a CZK
 * expense must not count against a USD budget. A budget with no matching expenses still gets a
 * chip, at zero — "we have a school budget and have not touched it" is information.
 *
 * @param budgets Active budgets
 * @param monthExpenses Expenses in the month being shown
 * @return One entry per budget, the most-spent first
 */
fun budgetProgress(budgets: List<Budget>, monthExpenses: List<Expense>): List<BudgetProgress> =
    budgets.map { budget ->
        val spent = monthExpenses
            .filter { it.category == budget.category && it.currency == budget.currency }
            .sumOf { it.amount }
        BudgetProgress(budget, spent)
    }.sortedByDescending { it.fraction }

/**
 * A scrollable strip of budget chips above the expense list, ending in an "+ Budget" action.
 *
 * Budgets already existed but lived entirely behind an unlabelled piggy-bank icon in the top
 * bar, so the Expenses screen carried no budget signal at all — you could blow through a limit
 * without the screen you were looking at ever mentioning it. Each chip states spent-of-limit
 * and carries a dot that turns amber at the budget's alert threshold and red past the limit.
 *
 * @param progress Budget progress entries, already ordered
 * @param onOpenBudgets Opens the budgets screen; also the "+ Budget" target
 * @param modifier Modifier for the strip
 */
@Composable
fun BudgetChips(
    progress: List<BudgetProgress>,
    onOpenBudgets: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        progress.forEach { entry ->
            val format = currencyFormat(entry.budget.currency)
            PillChip(
                label = stringResource(
                    R.string.expenses_budget_chip,
                    stringResource(entry.budget.category.labelRes),
                    format.format(entry.spent),
                    format.format(entry.budget.monthlyLimit)
                ),
                contentColor = MaterialTheme.colorScheme.onSurface,
                leadingDot = entry.dotColor(),
                onClick = onOpenBudgets
            )
        }
        PillChip(
            label = stringResource(R.string.expenses_budget_add),
            onClick = onOpenBudgets
        )
    }
}

/** Green while comfortably inside, amber past the alert threshold, red past the limit. */
@Composable
private fun BudgetProgress.dotColor(): Color = when {
    isOverLimit -> MaterialTheme.colorScheme.error
    isNearLimit -> CoPlanlyBudgetWarning
    else -> MaterialTheme.colorScheme.tertiary
}

/**
 * Amber for "close to the limit".
 *
 * Material 3 has no warning role between `tertiary` and `error`, and borrowing `error` for a
 * budget at 85% would say "something is wrong" when nothing is. Defined here rather than in the
 * palette because this strip is its only use; promote it to `CoPlanlyColors` if a second screen
 * needs the same idea.
 */
private val CoPlanlyBudgetWarning = Color(0xFFF5C05B)
