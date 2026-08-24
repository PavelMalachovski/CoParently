package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.calendar.ParentFilter
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import androidx.compose.foundation.layout.ExperimentalLayoutApi as FoundationExperimentalLayoutApi

/**
 * Bottom sheet holding every calendar filter: whose events to show, event type visibility,
 * custom type creation and the holiday switch.
 *
 * The whose-events control used to be a permanent row under the header. It moved in here so the
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
    parentNames: ParentNames,
    friendName: String?,
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
                parentNames = parentNames,
                selected = parentFilter,
                onSelected = onParentFilterChange
            )

            // The friend is a chip below the row rather than a fourth segment: a segment is
            // about a third of the sheet's width and the three parent labels already clip at
            // their fallbacks, so a fourth would clip all of them. It also reads correctly —
            // a friend is not a fourth owner, it is a narrowing of "show me where they are
            // expected" (item 16). Absent entirely when the family has no friend.
            friendName?.let { name ->
                FilterPill(
                    label = name,
                    selected = parentFilter == ParentFilter.FRIEND,
                    color = CoPlanlyColors.FriendTeal,
                    onClick = {
                        onParentFilterChange(
                            if (parentFilter == ParentFilter.FRIEND) {
                                ParentFilter.BOTH
                            } else {
                                ParentFilter.FRIEND
                            }
                        )
                    }
                )
            }

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
 * A single on/off filter chip in a named colour — the calendar friend's (item 16).
 *
 * Its own composable rather than a `FilterChip`: the Material chip's selected state comes from
 * the theme's neutral `secondary` slot, and this one has to carry a *person's* colour, which is
 * the distinction `CoPlanlyColors` draws between identity and control.
 */
@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) color else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One segment per parent, plus Both.
 *
 * The two outer segments are named after the people who hold the slots, not after the slots —
 * a filter offering "Mom" and "Dad" told half the families using this app that one of them was
 * somebody they are not.
 *
 * Parent colours are applied directly from [CoPlanlyColors] rather than through the theme's
 * `secondary` slot: pink and blue mean parent identity in this product and nothing else.
 *
 * @param selected Currently active filter
 * @param parentNames Resolves a slot to that parent's name
 * @param onSelected Callback when a segment is chosen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentFilterSegments(
    selected: ParentFilter,
    parentNames: ParentNames,
    onSelected: (ParentFilter) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(
            Triple(ParentFilter.MOM, parentNames.labelFor("mom"), CoPlanlyColors.MomPink),
            Triple(ParentFilter.BOTH, stringResource(R.string.calendar_filter_both), MaterialTheme.colorScheme.primary),
            Triple(ParentFilter.DAD, parentNames.labelFor("dad"), CoPlanlyColors.DadBlue)
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
                // A segment is about a third of the sheet's width and SegmentedButton has a
                // fixed height, so a long label clips rather than wrapping. Names are now
                // arbitrary length, and the fallbacks are the worst case, not the best:
                // "Второй родитель" is 15 characters, "Другий з батьків" 16.
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected == filter) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
