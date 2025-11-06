package com.coparently.app.presentation.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoParentlyColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

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

    // Calculate current week start from selectedDate or selectedMonth
    // This is used as targetState for AnimatedContent, just like selectedDate in DayWeekView
    val currentWeekStart = remember(selectedDate, selectedMonth) {
        val referenceDate = selectedDate ?: selectedMonth.atDay(1)
        // Find the first day of the week containing the reference date
        var date = referenceDate
        while (date.dayOfWeek != firstDayOfWeek) {
            date = date.minusDays(1)
        }
        date
    }

    // Handle swipe to change weeks - exactly same logic as DayWeekView
    val totalDragState = remember { mutableFloatStateOf(0f) }
    var totalDrag by totalDragState

    // State for swipe direction - same as DayWeekView
    var swipeDirection by remember { mutableStateOf(0) } // -1 for down, 1 for up
    var isSwipeInProgress by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        // Weekday header (fixed, not animated)
        WeekdayHeader(firstDayOfWeek)

        // Animated calendar content with swipe gestures - same as DayWeekView
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val dragValue = totalDrag
                            if (kotlin.math.abs(dragValue) > 200f) {
                                // Same logic as DayWeekView: dragValue > 0 means down swipe
                                swipeDirection = if (dragValue > 0) -1 else 1
                                val weeksToAdd = if (dragValue > 0) -1 else 1
                                val newWeekStart = currentWeekStart.plusWeeks(weeksToAdd.toLong())

                                // Always use onDateChange for week navigation
                                // This will update selectedDate in ViewModel, which will trigger recomposition
                                // and currentWeekStart will be recalculated, triggering AnimatedContent animation
                                if (onDateChange != null) {
                                    onDateChange(newWeekStart)
                                } else {
                                    // Fallback to month change if onDateChange is not provided
                                    val newMonth = YearMonth.from(newWeekStart)
                                    if (newMonth != selectedMonth) {
                                        onMonthChange(newMonth)
                                    }
                                }
                            }
                            totalDrag = 0f
                            isSwipeInProgress = false
                        }
                    ) { _, dragAmount ->
                        totalDrag = totalDrag + dragAmount
                        // Mark as swipe in progress and consume to prevent clicks
                        if (kotlin.math.abs(totalDrag) > 5f) {
                            isSwipeInProgress = true
                        }
                    }
                }
        ) {
            AnimatedContent(
                targetState = currentWeekStart,
                transitionSpec = {
                    val direction = swipeDirection
                    (slideInVertically(
                        animationSpec = tween(300),
                        initialOffsetY = { fullHeight -> fullHeight * direction }
                    ) + fadeIn(animationSpec = tween(300))) togetherWith
                    (slideOutVertically(
                        animationSpec = tween(300),
                        targetOffsetY = { fullHeight -> -fullHeight * direction }
                    ) + fadeOut(animationSpec = tween(300)))
                },
                modifier = Modifier.fillMaxSize()
            ) { weekStart ->
                // Generate weeks starting from the selected week, not from the month start
                // This allows scrolling through weeks while showing 6 weeks at a time
                val monthWeeks = remember(weekStart) {
                    generateWeeksFromWeekStart(weekStart, firstDayOfWeek, weeksToShow = 6)
                }

                // Use the month from selectedMonth for highlighting current month days
                val displayMonth = selectedMonth

                Column(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    monthWeeks.forEach { week ->
                        WeekRow(
                            week = week,
                            selectedMonth = displayMonth,
                            events = events,
                            custodySchedules = custodySchedules,
                            weekFields = weekFields,
                            onDayClick = onDayClick,
                            isSwipeInProgress = isSwipeInProgress
                        )
                    }
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
                    // Note: In month view header, we don't show day numbers, only day names
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
    onDayClick: (LocalDate) -> Unit,
    isSwipeInProgress: Boolean = false
) {
    val weekNumber = remember(week) {
        week.firstOrNull()?.get(weekFields.weekOfWeekBasedYear()) ?: 0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Week number
        Box(
            modifier = Modifier
                .width(32.dp)
                .fillMaxSize()
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
            // Day number
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

            // Event indicators - compact text labels like in the reference screenshot
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

                // Show count indicator if more events
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
 * @param weekStart The first day of the week to start from
 * @param firstDayOfWeek The first day of the week (e.g., Monday or Sunday)
 * @param weeksToShow Number of weeks to display
 */
private fun generateWeeksFromWeekStart(
    weekStart: LocalDate,
    firstDayOfWeek: DayOfWeek,
    weeksToShow: Int = 6
): List<List<LocalDate>> {
    // Ensure weekStart is actually the start of a week
    var currentDate = weekStart
    while (currentDate.dayOfWeek != firstDayOfWeek) {
        currentDate = currentDate.minusDays(1)
    }

    val weeks = mutableListOf<List<LocalDate>>()

    // Generate exactly weeksToShow weeks starting from weekStart
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

/**
 * Generate weeks for the month view, including days from adjacent months
 * @param yearMonth The month to display
 * @param firstDayOfWeek The first day of the week (e.g., Monday or Sunday)
 * @param weeksToShow Number of weeks to display (default 7 for full screen)
 */
private fun generateWeeksForMonth(
    yearMonth: YearMonth,
    firstDayOfWeek: DayOfWeek,
    weeksToShow: Int = 7
): List<List<LocalDate>> {
    val firstOfMonth = yearMonth.atDay(1)

    // Find the first day to display (start of the first week)
    var currentDate = firstOfMonth
    while (currentDate.dayOfWeek != firstDayOfWeek) {
        currentDate = currentDate.minusDays(1)
    }

    return generateWeeksFromWeekStart(currentDate, firstDayOfWeek, weeksToShow)
}

