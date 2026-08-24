package com.coparently.app.presentation.childinfo.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Medication

/**
 * Editor for managing a list of medications.
 * Provides add, edit, and delete functionality for medications.
 *
 * @param medications Current list of medications
 * @param onAdd Callback when medication is added
 * @param onEdit Callback when medication is edited
 * @param onRemove Callback when medication is removed
 */
@Composable
fun MedicationEditor(
    medications: List<Medication>,
    onAdd: (Medication) -> Unit,
    onEdit: (Int, Medication) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isAddingNew by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // List of existing medications
        medications.forEachIndexed { index, medication ->
            AnimatedVisibility(
                visible = editingIndex != index,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                MedicationCard(
                    medication = medication,
                    onEdit = { editingIndex = index },
                    onDelete = { onRemove(index) }
                )
            }

            // Edit form
            if (editingIndex == index) {
                MedicationForm(
                    medication = medication,
                    onSave = {
                        onEdit(index, it)
                        editingIndex = null
                    },
                    onCancel = { editingIndex = null }
                )
            }
        }

        // Add new medication form
        AnimatedVisibility(
            visible = isAddingNew,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            MedicationForm(
                onSave = {
                    onAdd(it)
                    isAddingNew = false
                },
                onCancel = { isAddingNew = false }
            )
        }

        // Add button
        if (!isAddingNew && editingIndex == null) {
            OutlinedButton(
                onClick = { isAddingNew = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.childinfo_add))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.childinfo_add_medication))
            }
        }
    }
}

/**
 * Card displaying medication information.
 */
@Composable
private fun MedicationCard(
    medication: Medication,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = medication.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(
                        R.string.childinfo_medication_summary,
                        medication.dosage,
                        medication.frequency
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val notes = medication.notes
                if (!notes.isNullOrBlank()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.childinfo_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.childinfo_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Form for adding or editing a medication.
 */
@Composable
private fun MedicationForm(
    medication: Medication? = null,
    onSave: (Medication) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(medication?.name ?: "") }
    var dosage by remember { mutableStateOf(medication?.dosage ?: "") }
    var frequency by remember { mutableStateOf(medication?.frequency ?: "") }
    var notes by remember { mutableStateOf(medication?.notes ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (medication == null) {
                    stringResource(R.string.childinfo_new_medication)
                } else {
                    stringResource(R.string.childinfo_edit_medication)
                },
                style = MaterialTheme.typography.titleSmall
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.childinfo_medication_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = dosage,
                onValueChange = { dosage = it },
                label = { Text(stringResource(R.string.childinfo_dosage_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = frequency,
                onValueChange = { frequency = it },
                label = { Text(stringResource(R.string.childinfo_frequency_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.childinfo_notes_optional_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.childinfo_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (name.isNotBlank() && dosage.isNotBlank() && frequency.isNotBlank()) {
                            onSave(
                                Medication(
                                    name = name,
                                    dosage = dosage,
                                    frequency = frequency,
                                    notes = notes.ifBlank { null }
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank() && dosage.isNotBlank() && frequency.isNotBlank()
                ) {
                    Text(stringResource(R.string.childinfo_save))
                }
            }
        }
    }
}

