package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.common.ParentNames
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Offers one day to the other parent, from a long-press on the month grid.
 *
 * Long-press rather than a menu because the calendar's tap is already spoken for — it selects the
 * day and fills in the agenda card below — and the design refresh removed the unlabelled header
 * actions on purpose. A long-press on a day cell was unused.
 *
 * **This sheet never applies anything by itself.** It offers; the co-parent answers in the inbox.
 * A swap that applied itself would just be an edit, which the custody editor already does, and
 * the whole point of the feature is that a day changes hands only when both parents have said so.
 *
 * The two parents are named, never "Mom" or "Dad": every label goes through [ParentNames], as
 * everywhere else in this app.
 *
 * @param date The day being offered.
 * @param currentCustody The slot that has [date] now — `"mom"` or `"dad"`, or null when nothing
 *   answers for that day.
 * @param parentNames Resolves a slot to that parent's name.
 * @param onOffer Called with the slot taking the day and an optional note.
 * @param onDismiss Closes the sheet without offering anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySwapSheet(
    date: LocalDate,
    currentCustody: String?,
    parentNames: ParentNames,
    onOffer: (toParent: String, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    // The other slot. With no custody recorded for the day there is nothing to swap *from*, so
    // the sheet says so rather than guessing which parent would be giving the day up.
    val toParent = when (currentCustody) {
        "mom" -> "dad"
        "dad" -> "mom"
        else -> null
    }
    var note by remember { mutableStateOf("") }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.day_swap_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = date.format(dateFormatter),
                style = MaterialTheme.typography.bodyLarge
            )

            if (toParent == null) {
                Text(
                    text = stringResource(R.string.day_swap_needs_coparent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.day_swap_currently_with,
                        parentNames.labelFor(currentCustody)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.day_swap_would_go_to,
                        parentNames.labelFor(toParent)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.day_swap_note_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.day_swap_cancel))
                }
                if (toParent != null) {
                    Button(onClick = { onOffer(toParent, note.ifBlank { null }) }) {
                        Text(stringResource(R.string.day_swap_offer))
                    }
                }
            }
        }
    }
}
