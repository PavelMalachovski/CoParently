package com.coparently.app.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoParentlyColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

/**
 * Enhanced month view with week numbers and modern UX.
 * Shows calendar in month grid with week numbers on the left.
 * Supports vertical swipe gestures to navigate between weeks.
 */
@Composable
fun MonthView(
    selectedMonth: YearMonth,
    selectedDate: LocalDate? = null,
    events: List<Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    onDayClick: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onDateChange: ((LocalDate) -> Unit)? = null
) {
    val weekFields = remember { WeekFields.of(Locale.getDefault()) }
    val firstDayOfWeek = remember { weekFields.firstDayOfWeek }

    val referenceDate = selectedDate ?: selectedMonth.atDay(1)
    val currentWeekStart = remember(referenceDate, firstDayOfWeek) {
        alignToWeekStart(referenceDate, firstDayOfWeek)
    }

    val totalDragState = remember { mutableFloatStateOf(0f) }
    var totalDrag by totalDragState
    var isSwipeInProgress by remember { mutableStateOf(false) }

    val weeksBefore = 6
    val weeksAfter = 6
    val visibleWeeks = 6
    val weekSpacing = 4.dp

    val weeks = remember(currentWeekStart, selectedMonth, firstDayOfWeek) {
        (-weeksBefore..weeksAfter).map { offset ->
            val weekStart = currentWeekStart.plusWeeks(offset.toLong())
            generateWeeksFromWeekStart(
                weekStart = weekStart,
                firstDayOfWeek = firstDayOfWeek,
                weeksToShow = 1
            ).first()
        }
    }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = weeksBefore
    )

    LaunchedEffect(currentWeekStart) {
        totalDrag = 0f
        isSwipeInProgress = false
        lazyListState.scrollToItem(weeksBefore)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        WeekdayHeader(firstDayOfWeek)

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            val totalSpacing = weekSpacing * (visibleWeeks - 1)
            val weekRowHeight = remember(maxHeight) {
                val calculated = (maxHeight - totalSpacing) / visibleWeeks
                if (calculated < 48.dp) 48.dp else calculated
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentWeekStart) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                val dragValue = totalDrag
                                if (abs(dragValue) > 200f) {
                                    val weeksToAdd = if (dragValue > 0) -1 else 1
                                    val newWeekStart = currentWeekStart.plusWeeks(weeksToAdd.toLong())
                                    if (onDateChange != null) {
                                        onDateChange(newWeekStart)
                                    } else {
                                        val newMonth = YearMonth.from(newWeekStart)
                                        if (newMonth != selectedMonth) {
                                            onMonthChange(newMonth)
                                        }
                                    }
                                }
                                totalDrag = 0f
                                isSwipeInProgress = false
                            },
                            onDragCancel = {
                                totalDrag = 0f
                                isSwipeInProgress = false
                            }
                        ) { _, dragAmount ->
                            totalDrag += dragAmount
                            if (!isSwipeInProgress && abs(totalDrag) > 5f) {
                                isSwipeInProgress = true
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(weekSpacing),
                userScrollEnabled = false
            ) {
                items(
                    items = weeks,
                    key = { week -> week.firstOrNull() ?: currentWeekStart }
                ) { week ->
                    WeekRow(
                        week = week,
                        selectedMonth = selectedMonth,
                        events = events,
                        custodySchedules = custodySchedules,
                        weekFields = weekFields,
                        weekHeight = weekRowHeight,
                        onDayClick = onDayClick,
                        isSwipeInProgress = isSwipeInProgress
                    )
                }
            }
        }
    }
}

/**
 * Weekday header row (Mon, Tue, Wed, etc.) - 1.5x larger
 */
@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Week number column header
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(90.dp)
                .padding(end = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Weekday names
        val weekdays = remember(firstDayOfWeek) {
            val days = mutableListOf<DayOfWeek>()
            var current = firstDayOfWeek
            repeat(7) {
                days.add(current)
                current = current.plus(1)
            }
            days
        }

        weekdays.forEach { dayOfWeek ->
            val isToday = dayOfWeek == LocalDate.now().dayOfWeek

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(90.dp)
                    .background(
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = dayOfWeek.getDisplayName(
                            java.time.format.TextStyle.SHORT,
                            Locale.getDefault()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                        fontSize = 9.sp,
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Single week row with week number and day cells
 */
@Composable
private fun WeekRow(
    week: List<LocalDate>,
    selectedMonth: YearMonth,
    events: List<Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    weekFields: WeekFields,
    weekHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
    isSwipeInProgress: Boolean = false
) {
    val weekNumber = remember(week) {
        week.firstOrNull()?.get(weekFields.weekOfWeekBasedYear()) ?: 0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(weekHeight),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Week number
        Box(
            modifier = Modifier
                .width(32.dp)
                .fillMaxHeight()
                .padding(end = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = weekNumber.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Day cells
        week.forEach { date ->
            DayCell(
                date = date,
                isCurrentMonth = YearMonth.from(date) == selectedMonth,
                events = events.filter { it.startDateTime.toLocalDate() == date },
                custodySchedules = custodySchedules,
                onDayClick = onDayClick,
                isSwipeInProgress = isSwipeInProgress
            )
        }
    }
}

/**
 * Individual day cell with events preview
 */
@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    events: List<Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    onDayClick: (LocalDate) -> Unit,
    isSwipeInProgress: Boolean = false
) {
    val isToday = CustodyHelper.isToday(date)
    val custody = CustodyHelper.getCustodyForDate(date, custodySchedules)

    val backgroundColor = when {
        !isCurrentMonth -> MaterialTheme.colorScheme.surface
        custody == "mom" -> CoParentlyColors.MomPink.copy(alpha = 0.08f)
        custody == "dad" -> CoParentlyColors.DadBlue.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .padding(1.dp)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(enabled = !isSwipeInProgress) {
                if (!isSwipeInProgress) {
                    onDayClick(date)
                }
            }
            .padding(4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (isToday) 26.dp else 24.dp)
                    .background(
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        !isCurrentMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            if (events.isNotEmpty() && isCurrentMonth) {
                val eventToShow = events.firstOrNull()
                eventToShow?.let { event ->
                    val eventColor = when (event.parentOwner) {
                        "mom" -> CoParentlyColors.MomPink
                        "dad" -> CoParentlyColors.DadBlue
                        else -> MaterialTheme.colorScheme.tertiary
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = eventColor.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (events.size > 1) {
                    Text(
                        text = "+${events.size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Generate weeks starting from a specific week start date
 */
private fun generateWeeksFromWeekStart(
    weekStart: LocalDate,
    firstDayOfWeek: DayOfWeek,
    weeksToShow: Int = 6
): List<List<LocalDate>> {
    var currentDate = weekStart
    while (currentDate.dayOfWeek != firstDayOfWeek) {
        currentDate = currentDate.minusDays(1)
    }

    val weeks = mutableListOf<List<LocalDate>>()

    repeat(weeksToShow) {
        val week = mutableListOf<LocalDate>()
        repeat(7) {
            week.add(currentDate)
            currentDate = currentDate.plusDays(1)
        }
        weeks.add(week)
    }

    return weeks
}

private fun alignToWeekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
    var current = date
    while (current.dayOfWeek != firstDayOfWeek) {
        current = current.minusDays(1)
    }
    return current
}
