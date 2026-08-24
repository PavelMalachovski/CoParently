package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Budget
import kotlin.math.roundToInt

/**
 * A single budget card. Tapping it opens the sheet that edits or deletes the budget.
 *
 * The card was read-only, and its doc said so honestly: "there is no budget-edit screen yet, so
 * the card carries no tap affordance rather than offering one that does nothing." The right half
 * of that trade has now been paid — there is an editor — so the affordance can exist. Until then
 * a typo in a monthly limit was permanent.
 */
@Composable
fun BudgetItem(
    budget: Budget,
    spentAmount: Double,
    onClick: () -> Unit
) {
    val progress = if (budget.monthlyLimit > 0) (spentAmount / budget.monthlyLimit).coerceIn(0.0, 1.0) else 0.0
    val color = when {
        spentAmount >= budget.monthlyLimit -> Color.Red
        progress >= budget.alertThreshold -> Color(0xFFFFC107) // Amber
        else -> Color(0xFF4CAF50) // Green
    }

    val format = remember(budget.currency) { currencyFormat(budget.currency) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(budget.category.labelRes),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(
                        R.string.budget_spent_of_limit,
                        format.format(spentAmount),
                        format.format(budget.monthlyLimit)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            LinearProgressIndicator(
                progress = { progress.toFloat() },
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            Text(
                text = stringResource(R.string.budget_percent_used, (progress * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}
