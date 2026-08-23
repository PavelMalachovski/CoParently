package com.coparently.app.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
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
import com.coparently.app.domain.model.Vaccination
import com.coparently.app.presentation.childinfo.components.DatePickerDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Vaccination list editor: one row per [Vaccination] (name, formatted date or "no date",
 * remove button), then a name field, an optional date picker and an add button. When
 * [enabled] is false, rows render with no remove button and no add controls follow.
 *
 * Extracted from `MedicalProfileEditor` (where it was a private section) so the pet record
 * can share it — a vet asks the same question a paramedic does, and one editor means the two
 * never drift apart. The strings stay the `medical_vaccination_*` set for the same reason.
 *
 * @param vaccinations Current values
 * @param onAdd Called with the new entry
 * @param onRemove Called with the index to remove
 * @param enabled False renders values with no editing affordance at all
 * @param modifier Modifier for the container
 */
@Composable
fun VaccinationListEditor(
    vaccinations: List<Vaccination>,
    onAdd: (Vaccination) -> Unit,
    onRemove: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val noDateLabel = stringResource(R.string.medical_vaccination_no_date)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.medical_vaccinations_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (vaccinations.isEmpty() && !enabled) {
            Text(
                text = stringResource(R.string.medical_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        vaccinations.forEachIndexed { index, vaccination ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = vaccination.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = vaccination.date?.format(dateFormatter) ?: noDateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (enabled) {
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.medical_vaccination_remove)
                        )
                    }
                }
            }
        }

        if (enabled) {
            VaccinationAddRow(dateFormatter = dateFormatter, onAdd = onAdd)
        }
    }
}

/**
 * The add-a-vaccination controls: a toggle button that reveals a name field, a date-picker
 * trigger and a confirm button. Split out of [VaccinationListEditor] purely to keep that
 * function under the project's method-length limit.
 */
@Composable
private fun VaccinationAddRow(
    dateFormatter: DateTimeFormatter,
    onAdd: (Vaccination) -> Unit,
    modifier: Modifier = Modifier
) {
    var isAdding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { dateTime ->
                newDate = dateTime.toLocalDate()
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
            initialDate = newDate?.atStartOfDay()
        )
    }

    Column(modifier = modifier) {
        AnimatedVisibility(visible = isAdding, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.medical_vaccination_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = newDate?.format(dateFormatter)
                            ?: stringResource(R.string.medical_vaccination_date_label)
                    )
                }
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onAdd(Vaccination(name = newName, date = newDate))
                            newName = ""
                            newDate = null
                            isAdding = false
                        }
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.medical_item_add))
                }
            }
        }

        if (!isAdding) {
            OutlinedButton(onClick = { isAdding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.medical_vaccination_add))
            }
        }
    }
}
