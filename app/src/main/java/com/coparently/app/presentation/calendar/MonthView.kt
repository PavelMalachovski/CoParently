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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    events: List<Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    onDayClick: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onDateChange: ((LocalDate) -> Unit)? = null
) {
    val weekFields = remember { WeekFields.of(Locale.getDefault()) }
    val firstDayOfWeek = remember { weekFields.firstDayOfWeek }

    // Swipe gesture state
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var swipeDirection by remember { mutableStateOf(0) } // -1 for down, 1 for up

    // Use selectedMonth as reference for current date
    val currentDate = selectedMonth.atDay(15)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .pointerInput(selectedMonth) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        val swipeThreshold = 150f
                        if (kotlin.math.abs(dragOffset) > swipeThreshold) {
                            val newDate = when {
                                dragOffset > swipeThreshold -> {
                                    // Swipe down - previous week (go back)
                                    swipeDirection = -1
                                    currentDate.minusWeeks(1)
                                }
                                dragOffset < -swipeThreshold -> {
                                    // Swipe up - next week (go forward)
                                    swipeDirection = 1
                                    currentDate.plusWeeks(1)
                                }
                                else -> currentDate
                            }

                            if (newDate != currentDate) {
                                if (onDateChange != null) {
                                    onDateChange(newDate)
                                } else {
                                    onMonthChange(YearMonth.from(newDate))
                                }
                            }
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    }
                ) { _, dragAmount ->
                    dragOffset += dragAmount
                }
            }
    ) {
        // Weekday header (fixed, not animated)
        WeekdayHeader(firstDayOfWeek)

        // Animated calendar content
        AnimatedContent(
            targetState = selectedMonth,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp) // Space for header
        ) { month ->
            // Calendar grid with week numbers
            val monthWeeks = remember(month) {
                generateWeeksForMonth(month, firstDayOfWeek, weeksToShow = 7)
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                monthWeeks.forEach { week ->
                    WeekRow(
                        week = week,
                        selectedMonth = month,
                        events = events,
                        custodySchedules = custodySchedules,
                        weekFields = weekFields,
                        onDayClick = onDayClick
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
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Week number column header
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.SHORT,
                        Locale.getDefault()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    onDayClick: (LocalDate) -> Unit
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
                onDayClick = onDayClick
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
    onDayClick: (LocalDate) -> Unit
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
            .clickable { onDayClick(date) }
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

    val weeks = mutableListOf<List<LocalDate>>()

    // Generate exactly weeksToShow weeks
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

