package com.coparently.app.presentation.childinfo.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R

/**
 * Editor for managing a list of allergies.
 * Displays allergies as chips with ability to add and remove.
 *
 * @param allergies Current list of allergies
 * @param onAdd Callback when allergy is added
 * @param onRemove Callback when allergy is removed
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AllergyEditor(
    allergies: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isAddingNew by remember { mutableStateOf(false) }
    var newAllergy by remember { mutableStateOf("") }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Display existing allergies as chips
        if (allergies.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                allergies.forEachIndexed { index, allergy ->
                    AssistChip(
                        onClick = { },
                        label = { Text(allergy) },
                        trailingIcon = {
                            IconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.childinfo_remove),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        // Add new allergy form
        AnimatedVisibility(
            visible = isAddingNew,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newAllergy,
                    onValueChange = { newAllergy = it },
                    label = { Text(stringResource(R.string.childinfo_allergy_label)) },
                    placeholder = { Text(stringResource(R.string.childinfo_allergy_placeholder)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                Button(
                    onClick = {
                        if (newAllergy.isNotBlank()) {
                            onAdd(newAllergy)
                            newAllergy = ""
                            isAddingNew = false
                        }
                    },
                    enabled = newAllergy.isNotBlank()
                ) {
                    Text(stringResource(R.string.childinfo_add))
                }
            }
        }

        // Add button
        if (!isAddingNew) {
            OutlinedButton(
                onClick = { isAddingNew = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.childinfo_add))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.childinfo_add_allergy))
            }
        }
    }
}

