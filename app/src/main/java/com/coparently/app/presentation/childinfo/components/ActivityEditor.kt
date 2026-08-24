package com.coparently.app.presentation.childinfo.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
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
import com.coparently.app.domain.model.Activity

/**
 * Editor for managing a list of child activities.
 * Provides add, edit, and delete functionality for activities.
 *
 * @param activities Current list of activities
 * @param onAdd Callback when activity is added
 * @param onEdit Callback when activity is edited
 * @param onRemove Callback when activity is removed
 */
@Composable
fun ActivityEditor(
    activities: List<Activity>,
    onAdd: (Activity) -> Unit,
    onEdit: (Int, Activity) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isAddingNew by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // List of existing activities
        activities.forEachIndexed { index, activity ->
            AnimatedVisibility(
                visible = editingIndex != index,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                ActivityCard(
                    activity = activity,
                    onEdit = { editingIndex = index },
                    onDelete = { onRemove(index) }
                )
            }

            // Edit form
            if (editingIndex == index) {
                ActivityForm(
                    activity = activity,
                    onSave = {
                        onEdit(index, it)
                        editingIndex = null
                    },
                    onCancel = { editingIndex = null }
                )
            }
        }

        // Add new activity form
        AnimatedVisibility(
            visible = isAddingNew,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            ActivityForm(
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
                Text(stringResource(R.string.childinfo_add_activity))
            }
        }
    }
}

/**
 * Card displaying activity information.
 */
@Composable
private fun ActivityCard(
    activity: Activity,
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
                    text = activity.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = activity.schedule,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val location = activity.location
                if (!location.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.childinfo_activity_location_marker, location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val contactPerson = activity.contactPerson
                val contactPhone = activity.contactPhone
                if (!contactPerson.isNullOrBlank()) {
                    Text(
                        text = if (!contactPhone.isNullOrBlank()) {
                            stringResource(
                                R.string.childinfo_activity_contact_phone_marker,
                                contactPerson,
                                contactPhone
                            )
                        } else {
                            stringResource(R.string.childinfo_activity_contact_marker, contactPerson)
                        },
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
 * Form for adding or editing an activity.
 */
@Composable
private fun ActivityForm(
    activity: Activity? = null,
    onSave: (Activity) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(activity?.name ?: "") }
    var schedule by remember { mutableStateOf(activity?.schedule ?: "") }
    var location by remember { mutableStateOf(activity?.location ?: "") }
    var contactPerson by remember { mutableStateOf(activity?.contactPerson ?: "") }
    var contactPhone by remember { mutableStateOf(activity?.contactPhone ?: "") }

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
                text = if (activity == null) {
                    stringResource(R.string.childinfo_new_activity)
                } else {
                    stringResource(R.string.childinfo_edit_activity)
                },
                style = MaterialTheme.typography.titleSmall
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.childinfo_activity_name_label)) },
                placeholder = { Text(stringResource(R.string.childinfo_activity_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = schedule,
                onValueChange = { schedule = it },
                label = { Text(stringResource(R.string.childinfo_schedule_label)) },
                placeholder = { Text(stringResource(R.string.childinfo_schedule_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text(stringResource(R.string.childinfo_location_optional_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = contactPerson,
                onValueChange = { contactPerson = it },
                label = { Text(stringResource(R.string.childinfo_contact_person_optional_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = contactPhone,
                onValueChange = { contactPhone = it },
                label = { Text(stringResource(R.string.childinfo_contact_phone_optional_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
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
                        if (name.isNotBlank() && schedule.isNotBlank()) {
                            onSave(
                                Activity(
                                    name = name,
                                    schedule = schedule,
                                    location = location.ifBlank { null },
                                    contactPerson = contactPerson.ifBlank { null },
                                    contactPhone = contactPhone.ifBlank { null }
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank() && schedule.isNotBlank()
                ) {
                    Text(stringResource(R.string.childinfo_save))
                }
            }
        }
    }
}

