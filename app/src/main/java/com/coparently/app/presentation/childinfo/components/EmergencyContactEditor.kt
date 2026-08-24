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
import com.coparently.app.domain.model.EmergencyContact

/**
 * Editor for managing emergency contacts.
 * Provides add, edit, and delete functionality for emergency contacts.
 *
 * @param contacts Current list of emergency contacts
 * @param onAdd Callback when contact is added
 * @param onEdit Callback when contact is edited
 * @param onRemove Callback when contact is removed
 */
@Composable
fun EmergencyContactEditor(
    contacts: List<EmergencyContact>,
    onAdd: (EmergencyContact) -> Unit,
    onEdit: (Int, EmergencyContact) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isAddingNew by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // List of existing contacts
        contacts.forEachIndexed { index, contact ->
            AnimatedVisibility(
                visible = editingIndex != index,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                EmergencyContactCard(
                    contact = contact,
                    onEdit = { editingIndex = index },
                    onDelete = { onRemove(index) }
                )
            }

            // Edit form
            if (editingIndex == index) {
                EmergencyContactForm(
                    contact = contact,
                    onSave = {
                        onEdit(index, it)
                        editingIndex = null
                    },
                    onCancel = { editingIndex = null }
                )
            }
        }

        // Add new contact form
        AnimatedVisibility(
            visible = isAddingNew,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            EmergencyContactForm(
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
                Text(stringResource(R.string.childinfo_add_emergency_contact))
            }
        }
    }
}

/**
 * Card displaying emergency contact information.
 */
@Composable
private fun EmergencyContactCard(
    contact: EmergencyContact,
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
                    text = contact.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = contact.relationship,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.childinfo_contact_phone_marker, contact.phone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val alternatePhone = contact.alternatePhone
                if (!alternatePhone.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.childinfo_contact_alt_phone_marker, alternatePhone),
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
 * Form for adding or editing an emergency contact.
 */
@Composable
private fun EmergencyContactForm(
    contact: EmergencyContact? = null,
    onSave: (EmergencyContact) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var relationship by remember { mutableStateOf(contact?.relationship ?: "") }
    var phone by remember { mutableStateOf(contact?.phone ?: "") }
    var alternatePhone by remember { mutableStateOf(contact?.alternatePhone ?: "") }

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
                text = if (contact == null) {
                    stringResource(R.string.childinfo_new_emergency_contact)
                } else {
                    stringResource(R.string.childinfo_edit_emergency_contact)
                },
                style = MaterialTheme.typography.titleSmall
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.childinfo_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = relationship,
                onValueChange = { relationship = it },
                label = { Text(stringResource(R.string.childinfo_relationship_label)) },
                placeholder = { Text(stringResource(R.string.childinfo_relationship_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(stringResource(R.string.childinfo_phone_number_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = alternatePhone,
                onValueChange = { alternatePhone = it },
                label = { Text(stringResource(R.string.childinfo_alternate_phone_optional_label)) },
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
                        if (name.isNotBlank() && relationship.isNotBlank() && phone.isNotBlank()) {
                            onSave(
                                EmergencyContact(
                                    name = name,
                                    relationship = relationship,
                                    phone = phone,
                                    alternatePhone = alternatePhone.ifBlank { null }
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank() && relationship.isNotBlank() && phone.isNotBlank()
                ) {
                    Text(stringResource(R.string.childinfo_save))
                }
            }
        }
    }
}

