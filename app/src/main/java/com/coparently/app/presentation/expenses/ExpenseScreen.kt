package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Expense
import com.coparently.app.presentation.common.animations.AnimatedEmptyState
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    onEditExpense: (String) -> Unit = {},
    onOpenBudgets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onSettleUp: (String) -> Unit = {},
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val expenses by viewModel.expenses.collectAsState()
    val monthExpenses by viewModel.monthExpenses.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val balancesByCurrency by viewModel.balancesByCurrency.collectAsState()
    val roleByUid by viewModel.roleByUid.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.expenses_deleted)
    val undoLabel = stringResource(R.string.expenses_deleted_undo)

    // Delete now, offer Undo — the same shape EventListScreen uses. The receipt photo is only
    // purged once the window closes, because a deleted photo cannot be brought back and Undo
    // has to restore the expense intact.
    val deleteWithUndo: (Expense) -> Unit = { expense ->
        viewModel.deleteExpense(expense.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreExpense(expense)
            } else if (expense.receiptUrl != null) {
                viewModel.purgeReceipt(expense.id)
            }
        }
    }

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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                MonthNavigator(
                    month = selectedMonth,
                    expenseCount = monthExpenses.size,
                    onPreviousMonth = viewModel::showPreviousMonth,
                    onNextMonth = viewModel::showNextMonth
                )

                if (monthExpenses.isEmpty()) {
                    // Other months may still hold expenses (e.g. an older receipt), so this is a
                    // per-month empty note, not the global empty state handled above.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.expenses_month_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val monthLabel = remember(selectedMonth) {
                        selectedMonth.month
                            .getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale.getDefault())
                            .replaceFirstChar { it.uppercase() }
                    }
                    // One summary card per currency present this month — the app does no FX
                    // conversion, so a mixed-currency month is shown as separate honest totals
                    // rather than one wrong sum.
                    balancesByCurrency.forEach { currencyBalance ->
                        ExpenseSummaryHeader(
                            balance = currencyBalance.balance,
                            currency = currencyBalance.currency,
                            onSettleUp = onSettleUp,
                            monthLabel = monthLabel,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                    }

                    ExpenseList(
                        expenses = monthExpenses,
                        roleByUid = roleByUid,
                        onDelete = deleteWithUndo,
                        onExpenseClick = { onEditExpense(it.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Month switcher above the expense list: a back/forward pair around the selected month's name
 * and its expense count. Lets the list reach months other than the current one, so an expense
 * dated in the past (e.g. an older receipt) is findable instead of silently missing.
 *
 * @param month Month currently shown
 * @param expenseCount Number of expenses in [month]
 * @param onPreviousMonth Called to page one month back
 * @param onNextMonth Called to page one month forward
 */
@Composable
private fun MonthNavigator(
    month: YearMonth,
    expenseCount: Int,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val label = remember(month) {
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.expenses_prev_month)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = pluralStringResource(R.plurals.expenses_count, expenseCount, expenseCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onNextMonth) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.expenses_next_month)
            )
        }
    }
}
