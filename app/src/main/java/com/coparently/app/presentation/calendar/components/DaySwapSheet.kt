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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.common.ParentNames
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Offers one day, or a run of consecutive days, to the other parent.
 *
 * Reached by long-pressing a day on the month grid; long-pressing and then tapping further days
 * extends the run. Long-press rather than a menu because the calendar's tap is already spoken for
 * — it selects the day and opens Day view — and the design refresh removed the unlabelled header
 * actions on purpose.
 *
 * **This sheet never applies anything by itself.** It offers; the co-parent answers in the inbox.
 * A swap that applied itself would just be an edit, which the custody editor already does, and
 * the whole point of the feature is that a day changes hands only when both parents have said so.
 *
 * **Each day flips to its own complement.** A run that spans a handover therefore exchanges in
 * both directions rather than handing every day to one parent, which is what the grid already
 * showed the parent when they selected it. Days the app has no custody answer for are excluded
 * from the offer and counted out loud, rather than being guessed at.
 *
 * The two parents are named, never "Mom" or "Dad": every label goes through [ParentNames], as
 * everywhere else in this app.
 *
 * @param dates The selected days, soonest first. Never empty.
 * @param custodyFor The slot that has a given day now — `"mom"` or `"dad"`, or null when nothing
 *   answers for it.
 * @param parentNames Resolves a slot to that parent's name.
 * @param onOffer Called with the days that can actually be offered and an optional note.
 * @param onDismiss Closes the sheet without offering anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySwapSheet(
    dates: List<LocalDate>,
    custodyFor: (LocalDate) -> String?,
    parentNames: ParentNames,
    onOffer: (dates: List<LocalDate>, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    // Only days with a custody answer can be exchanged: with nothing recorded there is nothing to
    // swap *from*, so the sheet says so rather than guessing which parent would give the day up.
    val offerable = remember(dates) { dates.filter { custodyFor(it) != null } }
    var note by remember { mutableStateOf("") }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(
                    if (dates.size == 1) {
                        R.string.day_swap_sheet_title
                    } else {
                        R.string.day_swap_sheet_title_range
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (dates.size == 1) {
                    dates.first().format(dateFormatter)
                } else {
                    stringResource(
                        R.string.day_swap_range,
                        dates.first().format(dateFormatter),
                        dates.last().format(dateFormatter)
                    )
                },
                style = MaterialTheme.typography.bodyLarge
            )

            if (offerable.isEmpty()) {
                Text(
                    text = stringResource(R.string.day_swap_needs_coparent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // The count leads, because it is the thing the co-parent will be asked about and
                // the thing a parent selecting a week wants confirmed before they commit.
                Text(
                    text = context.resources.getQuantityString(
                        R.plurals.day_swap_day_count,
                        offerable.size,
                        offerable.size
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                DirectionLines(offerable, custodyFor, parentNames)
                if (offerable.size < dates.size) {
                    Text(
                        text = context.resources.getQuantityString(
                            R.plurals.day_swap_skipped_days,
                            dates.size - offerable.size,
                            dates.size - offerable.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                if (offerable.isNotEmpty()) {
                    Button(onClick = { onOffer(offerable, note.ifBlank { null }) }) {
                        Text(stringResource(R.string.day_swap_offer))
                    }
                }
            }
        }
    }
}

/**
 * Who gives up how many days, one line per direction.
 *
 * A run that stays on one side of every handover produces a single line and reads exactly as the
 * old single-day sheet did. A run that crosses one produces two, which is the honest description
 * of what the offer does — and the thing a "N days would go to X" summary would get wrong.
 */
@Composable
private fun DirectionLines(
    dates: List<LocalDate>,
    custodyFor: (LocalDate) -> String?,
    parentNames: ParentNames
) {
    val context = LocalContext.current
    val bySlot = dates.groupBy { custodyFor(it) }
    // Slot order, not encounter order, so both phones describe the same offer the same way.
    listOf("mom", "dad").forEach { slot ->
        val days = bySlot[slot] ?: return@forEach
        val toParent = if (slot == "mom") "dad" else "mom"
        Text(
            text = context.resources.getQuantityString(
                R.plurals.day_swap_direction,
                days.size,
                days.size,
                parentNames.labelFor(slot),
                parentNames.labelFor(toParent)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
