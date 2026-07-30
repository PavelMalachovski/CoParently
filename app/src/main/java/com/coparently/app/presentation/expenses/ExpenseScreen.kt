package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.presentation.common.animations.AnimatedEmptyState

/** Fallback currency when the month has no expenses to take one from. */
private const val DEFAULT_CURRENCY = "USD"

/**
 * Expense list screen — a top-level bottom-navigation destination.
 *
 * Leads with the month's who-paid-what split and settle-up balance, then this month's
 * expenses; budgets open from the top-bar action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(
    onAddExpense: () -> Unit,
    onOpenBudgets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onSettleUp: (String) -> Unit = {},
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val expenses by viewModel.expenses.collectAsState()
    val monthExpenses by viewModel.expensesThisMonth.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val roleByUid by viewModel.roleByUid.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.expenses_title)) },
                actions = {
                    onOpenBudgets?.let { openBudgets ->
                        IconButton(onClick = openBudgets) {
                            Icon(
                                imageVector = Icons.Default.Savings,
                                contentDescription = stringResource(R.string.expenses_open_budgets)
                            )
                        }
                    }
                    onOpenSettings?.let { openSettings ->
                        IconButton(onClick = openSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddExpense) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.expenses_add))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (expenses.isEmpty()) {
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedEmptyState(
                        icon = Icons.Default.ReceiptLong,
                        title = stringResource(R.string.expenses_empty_title),
                        description = stringResource(R.string.expenses_empty_description),
                        actionText = stringResource(R.string.expenses_add),
                        onActionClick = onAddExpense
                    )
                }
            } else {
                ExpenseSummaryHeader(
                    balance = balance,
                    currency = monthExpenses.firstOrNull()?.currency ?: DEFAULT_CURRENCY,
                    onSettleUp = onSettleUp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = stringResource(R.string.expenses_this_month),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.expenses_count,
                            monthExpenses.size,
                            monthExpenses.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ExpenseList(
                    expenses = monthExpenses,
                    roleByUid = roleByUid,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
