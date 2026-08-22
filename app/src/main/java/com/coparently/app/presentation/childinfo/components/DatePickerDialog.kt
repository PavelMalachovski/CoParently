package com.coparently.app.presentation.childinfo.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.coparently.app.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Material3 Date Picker Dialog for selecting child's date of birth.
 *
 * @param onDateSelected Callback when date is selected
 * @param onDismiss Callback when dialog is dismissed
 * @param initialDate Initial date to show in picker
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    onDateSelected: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
    initialDate: LocalDateTime? = null
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: System.currentTimeMillis()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedDate = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(millis),
                            ZoneId.systemDefault()
                        )
                        onDateSelected(selectedDate)
                    }
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.childinfo_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.childinfo_cancel))
            }
        }
    ) {
        DatePicker(
            state = datePickerState,
            showModeToggle = true
        )
    }
}

