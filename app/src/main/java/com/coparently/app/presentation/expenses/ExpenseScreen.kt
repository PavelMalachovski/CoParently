package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Expense
import com.coparently.app.presentation.common.animations.AnimatedEmptyState
import com.coparently.app.presentation.common.rememberParentNames
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Expense list screen — a top-level bottom-navigation destination.
 *
 * Leads with one card carrying the month, the who-paid-what split and the settle-up balance,
 * then a segmented control choosing between two views of that month: the **list** (budget chips
 * and this month's expenses) or the **analytics** (a pie by category and a sorted table).
 *
 * Both views share the one month control in the summary card. Analytics is deliberately not a
 * route of its own: it would need a second month control, and the two could drift — a parent
 * looking at August's chart and September's list, with nothing on screen saying so.
 *
 * The August 2026 refresh merged the standalone month navigator into the summary card (the
 * screen used to spend three stacked headers before the first row) and surfaced budgets here
 * instead of leaving them behind an unlabelled top-bar icon.
 *
 * @param onAddExpense Opens the add-expense form
 * @param onEditExpense Opens an expense for editing
 * @param onOpenBudgets Opens the budgets screen; null hides the budget affordances
 * @param onOpenSettings Opens settings
 * @param onSettleUp Called with a drafted settle-up message, which the user then sends
 * @param viewModel Expense state
 * @param budgetViewModel Budget state, for the chip strip
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // one callback per navigation target this screen offers
fun ExpenseScreen(
    onAddExpense: () -> Unit,
    onEditExpense: (String) -> Unit = {},
    onOpenBudgets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onSettleUp: (String) -> Unit = {},
    viewModel: ExpenseViewModel = hiltViewModel(),
    budgetViewModel: BudgetViewModel = hiltViewModel()
) {
    val expenses by viewModel.expenses.collectAsState()
    val monthExpenses by viewModel.monthExpenses.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val balancesByCurrency by viewModel.balancesByCurrency.collectAsState()
    val roleByUid by viewModel.roleByUid.collectAsState()
    val parentNames = rememberParentNames(viewModel.parents.collectAsState().value)
    val budgets by budgetViewModel.budgets.collectAsState()
    val breakdowns by viewModel.breakdowns.collectAsState()
    val selectedBreakdown by viewModel.selectedBreakdown.collectAsState()
    val analyticsPayers by viewModel.analyticsPayers.collectAsState()
    val analyticsPayer by viewModel.analyticsPayer.collectAsState()

    // Which of the two views the month is shown in. `rememberSaveable`, so a rotation does not
    // silently drop a parent back to the list they had switched away from.
    var showAnalytics by rememberSaveable { mutableStateOf(false) }

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
                    // Budgets used to live behind an unlabelled piggy-bank icon here. They are
                    // now visible on the screen itself as a chip strip, so this action is gone
                    // rather than duplicated.
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
                val monthNavigation = MonthNavigation(
                    label = rememberMonthLabel(selectedMonth),
                    expenseCount = monthExpenses.size,
                    onPrevious = viewModel::showPreviousMonth,
                    onNext = viewModel::showNextMonth
                )

                if (monthExpenses.isEmpty()) {
                    // The switcher has to stay reachable, or a month with no expenses becomes a
                    // dead end you cannot page out of. Other months may still hold expenses
                    // (e.g. an older receipt), so this is a per-month empty note, not the
                    // global empty state handled above.
                    MonthSwitcherBar(
                        navigation = monthNavigation,
                        modifier = Modifier
                            .monthSwipe(monthNavigation)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // Swipe-to-delete is why the populated list below is never a swipe
                            // surface — there are no rows here to conflict with the gesture, so
                            // this empty-month placeholder can safely carry month navigation too.
                            .monthSwipe(monthNavigation),
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
                    // rather than one wrong sum. Only the first card carries the month
                    // switcher; repeating it per currency would switch the same month N times.
                    balancesByCurrency.forEachIndexed { index, currencyBalance ->
                        ExpenseSummaryHeader(
                            balance = currencyBalance.balance,
                            currency = currencyBalance.currency,
                            parentNames = parentNames,
                            onSettleUp = onSettleUp,
                            monthLabel = monthLabel,
                            modifier = Modifier
                                .then(
                                    if (index == 0) Modifier.monthSwipe(monthNavigation) else Modifier
                                )
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            monthNavigation = monthNavigation.takeIf { index == 0 }
                        )
                    }

                    // One month control, two views of it. A separate analytics route would need
                    // its own month control, and the two could drift — a parent looking at
                    // August's chart and September's list with nothing on screen saying so.
                    ViewSwitcher(
                        showAnalytics = showAnalytics,
                        onSelect = { showAnalytics = it },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )

                    if (showAnalytics) {
                        ExpenseAnalytics(
                            breakdown = selectedBreakdown,
                            currencies = breakdowns.map { it.currency },
                            payers = analyticsPayers,
                            selectedPayer = analyticsPayer,
                            parentNames = parentNames,
                            expenses = monthExpenses,
                            roleByUid = roleByUid,
                            onSelectCurrency = viewModel::selectAnalyticsCurrency,
                            onSelectPayer = viewModel::selectAnalyticsPayer,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // Budgets belong to the list: they are about what is left to spend, not
                        // about what was spent.
                        onOpenBudgets?.let { openBudgets ->
                            val progress = remember(budgets, monthExpenses) {
                                budgetProgress(budgets, monthExpenses)
                            }
                            BudgetChips(progress = progress, onOpenBudgets = openBudgets)
                        }

                        ExpenseList(
                            expenses = monthExpenses,
                            roleByUid = roleByUid,
                            parentNames = parentNames,
                            onDelete = deleteWithUndo,
                            onExpenseClick = { onEditExpense(it.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * List or Analytics, over the same month.
 *
 * @param showAnalytics Whether the analytics view is the one showing
 * @param onSelect Switches view
 * @param modifier Modifier applied to the control
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitcher(
    showAnalytics: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // The row itself is labelled: two unlabelled-in-context buttons announce "List" and
    // "Analytics" with nothing saying what they switch.
    val label = stringResource(R.string.expense_analytics_view_label)
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
    ) {
        SegmentedButton(
            selected = !showAnalytics,
            onClick = { onSelect(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) {
            Text(stringResource(R.string.expense_analytics_tab_list))
        }
        SegmentedButton(
            selected = showAnalytics,
            onClick = { onSelect(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) {
            Text(stringResource(R.string.expense_analytics_tab_analytics))
        }
    }
}

/** The selected month formatted as "August 2026", capitalised for the current locale. */
@Composable
private fun rememberMonthLabel(month: YearMonth): String = remember(month) {
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
