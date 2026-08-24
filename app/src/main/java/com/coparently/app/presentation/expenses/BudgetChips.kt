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
 * without the screen you were looking at ever mentioning it. Each chip states spent-of-limit;
 * one past its alert threshold or its limit also says so in words and carries a warning shape,
 * so the status does not depend on telling two ambers apart (see [BudgetStatus]).
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
            val status = entry.status()
            val statusLabel = status.label()
            val amounts = stringResource(
                R.string.expenses_budget_chip,
                stringResource(entry.budget.category.labelRes),
                format.format(entry.spent),
                format.format(entry.budget.monthlyLimit)
            )
            PillChip(
                // The status word joins the label rather than replacing the dot. A chip in hand
                // stays exactly as it was — the two that need attention are the ones that grow.
                label = if (statusLabel == null) {
                    amounts
                } else {
                    stringResource(R.string.expenses_budget_chip_status, amounts, statusLabel)
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
                icon = status.icon,
                iconDescription = statusLabel,
                // Dot only while the icon is absent, so a warning chip carries one marker and
                // not two competing ones.
                leadingDot = if (status.icon == null) status.color() else null,
                onClick = onOpenBudgets
            )
        }
        PillChip(
            label = stringResource(R.string.expenses_budget_add),
            onClick = onOpenBudgets
        )
    }
}

// `dotColor` and the local `CoPlanlyBudgetWarning` moved to `BudgetStatus` (UX-10). The comment
// on the colour said to promote it "if a second screen needs the same idea" — `BudgetItem` had
// needed it all along and had been carrying its own, different amber.
