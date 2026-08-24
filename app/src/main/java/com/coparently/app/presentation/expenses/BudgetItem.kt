package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    // Shared with the chip strip (UX-10). This screen used to decide the same three states for
    // itself, in a third palette — `Color.Red`, `0xFFFFC107`, `0xFF4CAF50` — so one budget could
    // read as a different amber depending on which screen you were looking at.
    val status = BudgetProgress(budget, spentAmount).status()
    val color = status.color()
    val statusLabel = status.label()

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

            // The percentage stays `onSurface`. It used to be painted in the status colour,
            // which put the amber state at about 1.7:1 on a light background — status text
            // nobody could read, doing the work of a status nobody could see. The status is a
            // word beside it now, and the colour is left to the bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.budget_percent_used, (progress * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall
                )
                if (statusLabel != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        status.icon?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
