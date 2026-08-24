package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.calendar.CalendarViewMode
import com.coparently.app.presentation.theme.LayoutConstants
import com.coparently.app.utils.LightDarkPreviews
import com.coparently.app.utils.PreviewWrapper
import java.time.LocalDate
import java.time.YearMonth

/**
 * Calendar screen header — one row.
 *
 * Reworked by the August 2026 design review, which found four actions in the bar plus a
 * permanent 44dp segmented row underneath, two of them unlabelled glyphs whose meaning you had
 * to already know (`view_list` = weekly summary, `swap_horiz` = change requests).
 *
 * What is left: the month title, which is now itself the view-mode control (tap for
 * Month/Week/Day), a labelled "Today" pill, one labelled Filters action, and the gear that
 * every top-level screen carries. Change requests moved to an inline banner over the grid,
 * where there is room to say what is waiting; the weekly summary keeps its single entry point
 * on Home rather than a second unlabelled one here.
 *
 * @param selectedDate Currently selected date, whose month and year title the screen
 * @param viewMode Current calendar view mode, shown in the title menu
 * @param onViewModeChange Called with the mode picked from the title menu
 * @param onNavigateToToday Callback when user taps "Today"
 * @param onFiltersClick Opens the filter sheet
 * @param filtersActive Whether any filter is narrowing the calendar
 * @param onSettingsClick Optional callback for settings button, null to hide button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // header actions are this component's API surface
fun CalendarHeader(
    selectedDate: LocalDate,
    viewMode: CalendarViewMode,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onNavigateToToday: () -> Unit,
    onJumpToDate: () -> Unit,
    onFiltersClick: () -> Unit,
    filtersActive: Boolean = false,
    onSettingsClick: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            MonthTitle(
                selectedDate = selectedDate,
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
                onJumpToDate = onJumpToDate
            )
        },
        actions = {
            TodayButton(onClick = onNavigateToToday)
            FiltersButton(onClick = onFiltersClick, active = filtersActive)
            onSettingsClick?.let { onClick ->
                SettingsButton(onClick = onClick)
            }
        }
    )
}

/**
 * "July 2026 ▾" — the title doubles as the view-mode picker, and as the way to reach a date.
 *
 * Month/Week/Day used to occupy a permanent segmented row under the bar, spending a fixed 44dp
 * of a phone screen on a setting most people change rarely. Folding it into the title costs one
 * tap when you do want it and gives the grid the row back when you don't.
 *
 * **Jump to date** joins that menu rather than becoming a fifth action in the bar. The date
 * picker it opens was written, wired to a `showDatePicker` flag, and never set to true — forty
 * lines of unreachable UI, with no route to a date eight months out except swiping there. The
 * title is already what a person taps when they want to be somewhere else in time, so this is
 * where the route belongs; a new unlabelled glyph beside Today, Filters and the gear would cost
 * the header row the August refresh deliberately gave back to the grid.
 */
@Composable
private fun MonthTitle(
    selectedDate: LocalDate,
    viewMode: CalendarViewMode,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onJumpToDate: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val yearMonth = YearMonth.from(selectedDate)
    val monthLabel = "${
        yearMonth.month.getDisplayName(
            java.time.format.TextStyle.FULL_STANDALONE,
            java.util.Locale.getDefault()
        ).replaceFirstChar { it.uppercase() }
    } ${yearMonth.year}"

    Box {
        Row(
            // The one control in this bar that Material does not size for us: `FilterChip`,
            // `OutlinedButton` and `IconButton` all expand their own touch target to 48dp, and a
            // bare `clickable` Row does not. It measured about 28dp and announced no role, so
            // TalkBack did not call it a control at all.
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { menuOpen = true }
                .defaultMinSize(minHeight = LayoutConstants.MIN_TOUCH_TARGET)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.calendar_change_view_mode),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            CalendarViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(viewModeLabel(mode)) },
                    onClick = {
                        menuOpen = false
                        onViewModeChange(mode)
                    },
                    leadingIcon = {
                        if (mode == viewMode) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.calendar_jump_to_date)) },
                onClick = {
                    menuOpen = false
                    onJumpToDate()
                }
            )
        }
    }
}

/**
 * The single labelled filter action, replacing the segmented row's trailing chip.
 *
 * @param onClick Opens the filter sheet
 * @param active Whether any filter is currently narrowing the calendar
 */
@Composable
private fun FiltersButton(onClick: () -> Unit, active: Boolean) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = {
            Text(
                text = stringResource(R.string.calendar_filters_button),
                style = MaterialTheme.typography.labelMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.padding(end = 4.dp)
    )
}

/**
 * Compact outlined "Today" pill.
 *
 * Replaces a button whose entire label was the current day number — which told the user the
 * date but not that tapping it jumps the calendar there.
 */
@Composable
private fun TodayButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.padding(end = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.calendar_today_button),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Display label for a calendar view mode. */
@Composable
private fun viewModeLabel(mode: CalendarViewMode): String = when (mode) {
    CalendarViewMode.MONTH -> stringResource(R.string.calendar_viewmode_month)
    CalendarViewMode.WEEK -> stringResource(R.string.calendar_viewmode_week)
    CalendarViewMode.DAY -> stringResource(R.string.calendar_viewmode_day)
}

/**
 * Settings icon button.
 */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = stringResource(R.string.calendar_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== Previews ====================

@LightDarkPreviews
@Composable
private fun CalendarHeaderTodayPreview() {
    PreviewWrapper {
        CalendarHeader(
            selectedDate = LocalDate.now(),
            viewMode = CalendarViewMode.MONTH,
            onViewModeChange = {},
            onNavigateToToday = {},
            onJumpToDate = {},
            onFiltersClick = {},
            onSettingsClick = {}
        )
    }
}

@Preview(name = "Week mode, filters active", showBackground = true)
@Composable
private fun CalendarHeaderWeekPreview() {
    PreviewWrapper {
        CalendarHeader(
            selectedDate = LocalDate.of(2026, 7, 20),
            viewMode = CalendarViewMode.WEEK,
            onViewModeChange = {},
            onNavigateToToday = {},
            onJumpToDate = {},
            onFiltersClick = {},
            filtersActive = true,
            onSettingsClick = {}
        )
    }
}
