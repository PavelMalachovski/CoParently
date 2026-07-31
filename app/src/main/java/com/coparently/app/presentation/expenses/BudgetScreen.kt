package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.BudgetAlert
import com.coparently.app.presentation.common.animations.AnimatedEmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    onBack: (() -> Unit)? = null,
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val budgets by viewModel.budgets.collectAsState()
    val alerts by viewModel.activeAlerts.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshAlerts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.budgets_title)) },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.budgets_back)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // Only show the FAB when budgets exist; the empty state carries its own
            // "Add budget" action, so a second entry point would be redundant.
            if (budgets.isNotEmpty()) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.budget_add))
                }
            }
        }
    ) { padding ->
        if (budgets.isEmpty()) {
            AnimatedEmptyState(
                icon = Icons.Default.Savings,
                title = stringResource(R.string.budgets_empty_title),
                description = stringResource(R.string.budgets_empty_description),
                actionText = stringResource(R.string.budget_add),
                onActionClick = { showAddSheet = true }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Active alerts
                if (alerts.isNotEmpty()) {
                    AlertSection(alerts = alerts)
                }

                // Budget list
                LazyColumn {
                    items(budgets) { budget ->
                        // We need to fetch spent amount for each budget
                        // In a real app, this might be better handled in the VM with a combined state
                        var spentAmount by remember { mutableStateOf(0.0) }

                        LaunchedEffect(budget.id) {
                            spentAmount = viewModel.getSpentForBudget(budget.id)
                        }

                        BudgetItem(
                            budget = budget,
                            spentAmount = spentAmount
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddBudgetSheet(
            onDismiss = { showAddSheet = false },
            onSave = { category, monthlyLimit ->
                viewModel.addBudget(category = category, monthlyLimit = monthlyLimit)
                showAddSheet = false
            }
        )
    }
}

@Composable
fun AlertSection(alerts: List<BudgetAlert>) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = stringResource(R.string.budget_alerts_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        alerts.forEach { alert ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.budget_alert_exceeded,
                            stringResource(alert.category.labelRes)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(
                            R.string.budget_alert_percent_spent,
                            (alert.percentage * 100).toInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
