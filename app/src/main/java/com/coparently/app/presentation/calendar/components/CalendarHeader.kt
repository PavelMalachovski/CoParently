package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.calendar.CalendarViewMode
import com.coparently.app.utils.LightDarkPreviews
import com.coparently.app.utils.PreviewWrapper
import java.time.LocalDate
import java.time.YearMonth

/**
 * Calendar screen header.
 *
 * Two rows rather than three: the title carries a labelled subtitle ("Sat 25 · today") instead
 * of a bare day number floating in the actions, and view-mode selection moved out of a dropdown
 * on the title into an explicit segmented row ([CalendarViewModeBar]) below.
 *
 * All four actions are kept. The change-requests badge in particular signals work waiting on the
 * user, so it stays visible rather than moving into an overflow.
 *
 * @param selectedDate Currently selected date to display month/year
 * @param onNavigateToToday Callback when user taps "Today"
 * @param onSettingsClick Optional callback for settings button, null to hide button
 * @param onChangeRequestsClick Optional callback for the change-requests inbox, null to hide button
 * @param pendingChangeRequests Number of pending incoming change requests (badge on the inbox icon)
 * @param onWeeklySummaryClick Optional callback for the weekly summary, null to hide button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHeader(
    selectedDate: LocalDate,
    onNavigateToToday: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    onChangeRequestsClick: (() -> Unit)? = null,
    pendingChangeRequests: Int = 0,
    onWeeklySummaryClick: (() -> Unit)? = null
) {
    TopAppBar(
        title = { MonthTitle(selectedDate = selectedDate) },
        actions = {
            TodayButton(onClick = onNavigateToToday)

            onWeeklySummaryClick?.let { onClick ->
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(R.string.calendar_weekly_summary),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            onChangeRequestsClick?.let { onClick ->
                ChangeRequestsButton(
                    pendingCount = pendingChangeRequests,
                    onClick = onClick
                )
            }

            onSettingsClick?.let { onClick ->
                SettingsButton(onClick = onClick)
            }
        }
    )
}

/**
 * "July 2026" over a labelled subtitle.
 *
 * The subtitle is what the bare `25` in the actions used to be: a day number with no word
 * attached. Spelling it out ("Sat 25 · today") costs one line of small text and removes the
 * guesswork.
 */
@Composable
private fun MonthTitle(selectedDate: LocalDate) {
    val yearMonth = YearMonth.from(selectedDate)
    val monthLabel = "${
        yearMonth.month.getDisplayName(
            java.time.format.TextStyle.FULL_STANDALONE,
            java.util.Locale.getDefault()
        ).replaceFirstChar { it.uppercase() }
    } ${yearMonth.year}"

    val isToday = selectedDate == LocalDate.now()
    val dayLabel = selectedDate.format(
        java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.getDefault())
    )
    val subtitle = if (isToday) {
        stringResource(R.string.calendar_subtitle_today, dayLabel)
    } else {
        dayLabel
    }

    Column {
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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

/**
 * View-mode segments plus the Filters entry point.
 *
 * The three modes used to hide behind a chevron on the title; making them a segmented row shows
 * the current mode without a tap and removes one level of indirection. Filters (parent and event
 * type) sits on the same line, which is what lets the header collapse from three rows to two.
 *
 * @param viewMode Current calendar view mode
 * @param onViewModeChange Callback when a mode is selected
 * @param onFiltersClick Opens the filter sheet
 * @param filtersActive Whether any filter is narrowing the calendar (dot on the button)
 * @param modifier Modifier for the row
 */
@Composable
fun CalendarViewModeBar(
    viewMode: CalendarViewMode,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onFiltersClick: () -> Unit,
    filtersActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            CalendarViewMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == viewMode,
                    onClick = { onViewModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = CalendarViewMode.entries.size
                    ),
                    label = {
                        Text(
                            text = viewModeLabel(mode),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        FilterChip(
            selected = filtersActive,
            onClick = onFiltersClick,
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
            }
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
 * Change-requests inbox button with a badge for pending incoming requests.
 *
 * @param pendingCount Number of pending incoming requests; badge hidden when zero
 * @param onClick Callback when button is clicked
 */
@Composable
private fun ChangeRequestsButton(
    pendingCount: Int,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (pendingCount > 0) {
                    Badge { Text(pendingCount.toString()) }
                }
            }
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.calendar_change_requests),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
        Column {
            CalendarHeader(
                selectedDate = LocalDate.now(),
                onNavigateToToday = {},
                onSettingsClick = {},
                onChangeRequestsClick = {},
                pendingChangeRequests = 2,
                onWeeklySummaryClick = {}
            )
            CalendarViewModeBar(
                viewMode = CalendarViewMode.MONTH,
                onViewModeChange = {},
                onFiltersClick = {}
            )
        }
    }
}

@Preview(name = "Other day selected, week mode", showBackground = true)
@Composable
private fun CalendarHeaderWeekPreview() {
    PreviewWrapper {
        Column {
            CalendarHeader(
                selectedDate = LocalDate.of(2026, 7, 20),
                onNavigateToToday = {},
                onSettingsClick = {}
            )
            CalendarViewModeBar(
                viewMode = CalendarViewMode.WEEK,
                onViewModeChange = {},
                onFiltersClick = {},
                filtersActive = true
            )
        }
    }
}
