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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coparently.app.domain.model.Budget
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A single budget card. Read-only: there is no budget-edit screen yet, so the card carries no
 * tap affordance rather than offering one that does nothing.
 */
@Composable
fun BudgetItem(
    budget: Budget,
    spentAmount: Double
) {
    val progress = if (budget.monthlyLimit > 0) (spentAmount / budget.monthlyLimit).coerceIn(0.0, 1.0) else 0.0
    val color = when {
        spentAmount >= budget.monthlyLimit -> Color.Red
        progress >= budget.alertThreshold -> Color(0xFFFFC107) // Amber
        else -> Color(0xFF4CAF50) // Green
    }

    val format = NumberFormat.getCurrencyInstance(Locale.US)
    format.currency = java.util.Currency.getInstance(budget.currency)

    Card(
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
                    text = budget.category.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${format.format(spentAmount)} / ${format.format(budget.monthlyLimit)}",
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
                text = "${(progress * 100).roundToInt()}% used",
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}
