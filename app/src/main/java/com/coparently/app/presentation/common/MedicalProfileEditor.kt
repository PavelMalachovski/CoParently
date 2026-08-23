package com.coparently.app.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.BloodType
import com.coparently.app.domain.model.MedicalProfile

/**
 * Edits a [MedicalProfile], or renders one read-only.
 *
 * Stateless: it takes a profile and emits a new one, so the owning ViewModel stays the single
 * source of truth. One composable rather than a parent version and a child version, because a
 * paramedic asks the same questions of both.
 *
 * @param profile Current values
 * @param onChange Called with the whole updated profile on every edit
 * @param modifier Modifier for the container
 * @param enabled False renders values with **no** editing affordance at all — used for the
 *   co-parent's profile, where `firestore.rules` refuses the write anyway
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicalProfileEditor(
    profile: MedicalProfile,
    onChange: (MedicalProfile) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BloodTypeSection(
            bloodType = profile.bloodType,
            onSelect = { onChange(profile.copy(bloodType = it)) },
            enabled = enabled
        )

        MedicalStringListSection(
            label = stringResource(R.string.medical_intolerances_label),
            hint = stringResource(R.string.medical_intolerances_hint),
            values = profile.intolerances,
            onAdd = { entry -> onChange(profile.copy(intolerances = profile.intolerances + entry)) },
            onRemove = { index ->
                val updated = profile.intolerances.toMutableList().apply { removeAt(index) }
                onChange(profile.copy(intolerances = updated))
            },
            enabled = enabled
        )

        MedicalStringListSection(
            label = stringResource(R.string.medical_hereditary_label),
            hint = stringResource(R.string.medical_hereditary_hint),
            values = profile.hereditaryConditions,
            onAdd = { entry ->
                onChange(profile.copy(hereditaryConditions = profile.hereditaryConditions + entry))
            },
            onRemove = { index ->
                val updated = profile.hereditaryConditions.toMutableList().apply { removeAt(index) }
                onChange(profile.copy(hereditaryConditions = updated))
            },
            enabled = enabled
        )

        VaccinationListEditor(
            vaccinations = profile.vaccinations,
            onAdd = { vaccination -> onChange(profile.copy(vaccinations = profile.vaccinations + vaccination)) },
            onRemove = { index ->
                val updated = profile.vaccinations.toMutableList().apply { removeAt(index) }
                onChange(profile.copy(vaccinations = updated))
            },
            enabled = enabled
        )
    }
}

/**
 * Blood type field: an [ExposedDropdownMenuBox] over [BloodType.entries] when editable, a plain
 * label/value pair with no dropdown affordance at all when not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BloodTypeSection(
    bloodType: BloodType?,
    onSelect: (BloodType?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val notSetLabel = stringResource(R.string.medical_blood_type_not_set)
    val currentLabel = bloodType?.let { stringResource(it.labelRes()) } ?: notSetLabel
    val fieldLabel = stringResource(R.string.medical_blood_type_label)

    if (!enabled) {
        Column(modifier = modifier) {
            Text(
                text = fieldLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(fieldLabel) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(notSetLabel) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            BloodType.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(candidate.labelRes())) },
                    onClick = {
                        onSelect(candidate)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * A labelled list of free-text entries, following `AllergyEditor`'s shape (chips with a remove
 * affordance, plus a text field and an add button), generalised for [MedicalProfile.intolerances]
 * and [MedicalProfile.hereditaryConditions]. When [enabled] is false, entries render as plain,
 * non-interactive [PillChip]s (or an empty-state message) with no add row at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList") // one list-field anatomy: label, hint, values and its two callbacks, enabled
private fun MedicalStringListSection(
    label: String,
    hint: String,
    values: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!enabled) {
            if (values.isEmpty()) {
                Text(
                    text = stringResource(R.string.medical_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    values.forEach { value -> PillChip(label = value) }
                }
            }
            return
        }

        if (values.isNotEmpty()) {
            MedicalStringChips(values = values, onRemove = onRemove)
        }

        MedicalStringAddRow(hint = hint, onAdd = onAdd)
    }
}

/**
 * Chips for the current entries of a [MedicalStringListSection], each with a remove affordance —
 * split out purely to keep that function under the project's method-length limit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicalStringChips(values: List<String>, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        values.forEachIndexed { index, value ->
            AssistChip(
                onClick = {},
                label = { Text(value) },
                trailingIcon = {
                    IconButton(
                        onClick = { onRemove(index) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.medical_item_remove),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
    }
}

/**
 * The add-an-entry controls for a [MedicalStringListSection]: a toggle button that reveals a text
 * field and a confirm button — split out purely to keep that function under the project's
 * method-length limit.
 */
@Composable
private fun MedicalStringAddRow(hint: String, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var isAdding by remember { mutableStateOf(false) }
    var newValue by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        AnimatedVisibility(visible = isAdding, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    placeholder = { Text(hint) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (newValue.isNotBlank()) {
                            onAdd(newValue)
                            newValue = ""
                            isAdding = false
                        }
                    },
                    enabled = newValue.isNotBlank()
                ) {
                    Text(stringResource(R.string.medical_item_add))
                }
            }
        }

        if (!isAdding) {
            OutlinedButton(onClick = { isAdding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.medical_item_add))
            }
        }
    }
}

// The vaccination section lives in `VaccinationListEditor` (this package), extracted so the
// pet record can share it.
