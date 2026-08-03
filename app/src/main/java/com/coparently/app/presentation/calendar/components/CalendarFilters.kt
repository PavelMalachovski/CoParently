package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.calendar.ParentFilter
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import androidx.compose.foundation.layout.ExperimentalLayoutApi as FoundationExperimentalLayoutApi

/**
 * Bottom sheet holding every calendar filter: whose events to show, event type visibility,
 * custom type creation and the holiday switch.
 *
 * The Mom/Both/Dad control used to be a permanent row under the header. It moved in here so the
 * header could collapse to two rows; the trade-off is one extra tap to switch parent, which the
 * Filters action in the calendar header makes explicit.
 */
@OptIn(ExperimentalMaterial3Api::class, FoundationExperimentalLayoutApi::class)
@Composable
fun EventTypeFilterSheet(
    allEventTypes: List<String>,
    hiddenEventTypes: Set<String>,
    showHolidays: Boolean,
    parentFilter: ParentFilter,
    onParentFilterChange: (ParentFilter) -> Unit,
    onToggleType: (String) -> Unit,
    onAddCustomType: (String) -> Unit,
    onShowHolidaysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    val dims = dimensions()
    var newTypeName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.paddingMedium)
                .padding(bottom = dims.paddingMedium * 2),
            verticalArrangement = Arrangement.spacedBy(dims.paddingMedium)
        ) {
            Text(
                text = stringResource(R.string.calendar_filter_show),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            ParentFilterSegments(
                selected = parentFilter,
                onSelected = onParentFilterChange
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.calendar_filter_event_types),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.calendar_filter_hidden_types_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allEventTypes.forEach { type ->
                    val isVisible = type !in hiddenEventTypes
                    FilterChip(
                        selected = isVisible,
                        onClick = { onToggleType(type) },
                        label = {
                            Text(type.replaceFirstChar { it.uppercase() })
                        },
                        leadingIcon = if (isVisible) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.calendar_filter_type_visible),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null
                    )
                }
            }

            // Add custom event type
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newTypeName,
                    onValueChange = { newTypeName = it },
                    label = { Text(stringResource(R.string.calendar_filter_new_type_label)) },
                    placeholder = { Text(stringResource(R.string.calendar_filter_new_type_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        if (newTypeName.isNotBlank()) {
                            onAddCustomType(newTypeName)
                            newTypeName = ""
                        }
                    },
                    enabled = newTypeName.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(stringResource(R.string.calendar_filter_add_button))
                }
            }

            // Holidays toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.calendar_filter_holidays_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.calendar_filter_holidays_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showHolidays,
                    onCheckedChange = onShowHolidaysChange
                )
            }
        }
    }
}

/**
 * Mom / Both / Dad segments.
 *
 * Parent colours are applied directly from [CoPlanlyColors] rather than through the theme's
 * `secondary` slot: pink and blue mean parent identity in this product and nothing else.
 *
 * @param selected Currently active filter
 * @param onSelected Callback when a segment is chosen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentFilterSegments(
    selected: ParentFilter,
    onSelected: (ParentFilter) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(
            Triple(ParentFilter.MOM, stringResource(R.string.calendar_parent_mom), CoPlanlyColors.MomPink),
            Triple(ParentFilter.BOTH, stringResource(R.string.calendar_filter_both), MaterialTheme.colorScheme.primary),
            Triple(ParentFilter.DAD, stringResource(R.string.calendar_parent_dad), CoPlanlyColors.DadBlue)
        )
        options.forEachIndexed { index, (filter, label, color) ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = color.copy(alpha = 0.15f),
                    activeContentColor = color,
                    activeBorderColor = color
                ),
                icon = {}
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected == filter) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
