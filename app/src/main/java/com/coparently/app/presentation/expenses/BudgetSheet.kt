package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.presentation.common.ConfirmationDialog

/**
 * Bottom sheet for creating **or editing** a budget: a category and a monthly limit.
 * Save stays disabled until the limit is a positive number.
 *
 * One sheet for both, because they ask the same two questions. Editing did not exist at all
 * until now — `BudgetItem` had no click handler anywhere, `deleteBudget()` was never called, and
 * `updateBudget` had no ViewModel method — so a typo in a limit was permanent.
 *
 * The category is fixed when editing. A budget *is* the limit for its category, so moving one
 * across is not an edit but a different budget, and allowing it would quietly permit two budgets
 * for the same category. Delete this one and add that one.
 *
 * @param onDismiss Close the sheet without saving
 * @param onSave Called with the chosen category and monthly limit
 * @param existing The budget being edited, or null when creating one
 * @param onDelete Removes [existing]; ignored when creating
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetSheet(
    onDismiss: () -> Unit,
    onSave: (category: ExpenseCategory, monthlyLimit: Double) -> Unit,
    existing: Budget? = null,
    onDelete: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf(existing?.category ?: ExpenseCategory.EDUCATION) }
    var limit by remember { mutableStateOf(existing?.monthlyLimit?.asLimitText().orEmpty()) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val limitValue = limit.toDoubleOrNull()
    val isValid = limitValue != null && limitValue > 0

    if (confirmingDelete) {
        ConfirmationDialog(
            title = stringResource(R.string.budget_delete_confirm_title),
            message = stringResource(R.string.budget_delete_confirm_message),
            confirmText = stringResource(R.string.budget_delete),
            dismissText = stringResource(R.string.common_cancel),
            isDestructive = true,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(
                    if (existing == null) R.string.budget_add_title else R.string.budget_edit_title
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (existing == null) {
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = !categoryExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = stringResource(category.labelRes),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.budget_field_category)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        ExpenseCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(stringResource(cat.labelRes)) },
                                onClick = {
                                    category = cat
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
            } else {
                // No dropdown affordance at all rather than a disabled one: the category is not
                // something this sheet can change, and a greyed-out menu invites the tap anyway.
                OutlinedTextField(
                    value = stringResource(category.labelRes),
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text(stringResource(R.string.budget_field_category)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it },
                label = { Text(stringResource(R.string.budget_field_limit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { limitValue?.let { onSave(category, it) } },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.budget_save))
            }

            // Destructive action last and quiet, the anatomy Settings' sign-out uses: a red text
            // action that confirms, never a filled error button competing with Save.
            if (existing != null) {
                TextButton(
                    onClick = { confirmingDelete = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.budget_delete))
                }
            }
        }
    }
}

/**
 * A stored limit as the text a person would have typed: `1000`, not `1000.0`.
 *
 * Prefilling the field with Kotlin's `Double.toString()` would make every edit start by deleting
 * a `.0` the user never entered.
 */
private fun Double.asLimitText(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
