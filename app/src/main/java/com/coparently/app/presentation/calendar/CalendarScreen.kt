package com.coparently.app.presentation.calendar

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.presentation.event.EventViewModel
import com.coparently.app.presentation.theme.CoParentlyColors
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.YearMonth
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth as JavaYearMonth

/**
 * Main calendar screen showing monthly calendar view with events.
 * Enhanced with animations, custody indicators, and smooth transitions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onEventClick: (String) -> Unit,
    onAddEventClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    eventViewModel: EventViewModel = hiltViewModel(),
    calendarViewModel: CalendarViewModel = hiltViewModel()
) {
    val currentMonth = remember {
        val now = JavaYearMonth.now()
        YearMonth(now.year, now.monthValue)
    }
    val startMonth = remember {
        currentMonth.minusMonths(12)
    }
    val endMonth = remember {
        currentMonth.plusMonths(12)
    }
    val firstDayOfWeek = remember { firstDayOfWeekFromLocale() }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = firstDayOfWeek
    )

    val events by eventViewModel.events.collectAsState()
    val custodySchedules by calendarViewModel.custodySchedules.collectAsState()

    LaunchedEffect(calendarState.firstVisibleMonth) {
        val visibleMonth = calendarState.firstVisibleMonth.yearMonth
        val javaYearMonth = JavaYearMonth.of(visibleMonth.year, visibleMonth.month.value)
        val start = javaYearMonth.atDay(1).atStartOfDay()
        val end = javaYearMonth.atEndOfMonth().atTime(23, 59, 59)
        eventViewModel.loadEventsForDateRange(start, end)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = calendarState.firstVisibleMonth.yearMonth,
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                        }
                    ) { month ->
                        Text(stringResource(R.string.calendar_title))
                    }
                },
                actions = {
                    if (onSettingsClick != null) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.calendar_settings)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddEventClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.calendar_add_event)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Today's custody indicator
            if (custodySchedules.isNotEmpty()) {
                val today = LocalDate.now()
                val todayCustody = CustodyHelper.getCustodyForDate(today, custodySchedules)
                if (todayCustody != null) {
                    AnimatedContent(
                        targetState = todayCustody,
                        transitionSpec = {
                            slideInVertically(
                                animationSpec = tween(300),
                                initialOffsetY = { -it }
                            ) + fadeIn() togetherWith slideOutVertically(
                                animationSpec = tween(300),
                                targetOffsetY = { it }
                            ) + fadeOut()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { custody ->
                        CustodyIndicatorToday(custody = custody)
                    }
                }
            }

            HorizontalCalendar(
                state = calendarState,
                modifier = Modifier.weight(1f),
                monthHeader = { month ->
                    CalendarMonthHeader(month.yearMonth)
                },
                dayContent = { day ->
                    CalendarDayContent(
                        day = day,
                        events = events.filter { event ->
                            event.startDateTime.toLocalDate() == day.date
                        },
                        custodySchedules = custodySchedules,
                        onClick = { clickedDay ->
                            // Handle day click - show events or navigate
                        }
                    )
                }
            )
        }
    }
}

/**
 * Custody indicator for today's date.
 */
@Composable
private fun CustodyIndicatorToday(custody: String) {
    val backgroundColor = when (custody) {
        "mom" -> CoParentlyColors.MomPink.copy(alpha = 0.2f)
        "dad" -> CoParentlyColors.DadBlue.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val borderColor = when (custody) {
        "mom" -> CoParentlyColors.MomPink
        "dad" -> CoParentlyColors.DadBlue
        else -> MaterialTheme.colorScheme.outline
    }

    val textColor = when (custody) {
        "mom" -> CoParentlyColors.MomPinkDark
        "dad" -> CoParentlyColors.DadBlueDark
        else -> MaterialTheme.colorScheme.onSurface
    }

    val text = when (custody) {
        "mom" -> stringResource(R.string.custody_with_mom)
        "dad" -> stringResource(R.string.custody_with_dad)
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color = borderColor, shape = CircleShape)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        }
    }
}

@Composable
private fun CalendarMonthHeader(yearMonth: YearMonth) {
    AnimatedContent(
        targetState = yearMonth,
        transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically(
                animationSpec = tween(300),
                initialOffsetY = { fullHeight: Int -> -fullHeight }
            )) togetherWith (fadeOut(tween(300)) + slideOutVertically(
                animationSpec = tween(300),
                targetOffsetY = { fullHeight: Int -> fullHeight }
            ))
        }
    ) { month: YearMonth ->
        Text(
            text = "${month.month.name} ${month.year}",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun CalendarDayContent(
    day: CalendarDay,
    events: List<com.coparently.app.domain.model.Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    onClick: (CalendarDay) -> Unit
) {
    val isToday = CustodyHelper.isToday(day.date)
    val custody = CustodyHelper.getCustodyForDate(day.date, custodySchedules)

    // Animate scale for today
    val scale by animateFloatAsState(
        targetValue = if (isToday) 1.1f else 1f,
        animationSpec = tween(300),
        label = "dayScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .clickable { onClick(day) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            // Day number
            AnimatedContent(
                targetState = day.date.dayOfMonth,
                transitionSpec = {
                    scaleIn(tween(200)) + fadeIn() togetherWith scaleOut(tween(200)) + fadeOut()
                }
            ) { dayNumber ->
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            } else {
                                Color.Transparent
                            },
                            shape = CircleShape
                        )
                        .size(if (isToday) 32.dp else 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = dayNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            day.position == DayPosition.MonthDate -> {
                                if (isToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            }
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                    )
                }
            }

            // Custody indicator for the day
            if (custody != null && day.position == DayPosition.MonthDate) {
                val custodyColor = when (custody) {
                    "mom" -> CoParentlyColors.MomPink
                    "dad" -> CoParentlyColors.DadBlue
                    else -> Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .size(if (isToday) 8.dp else 6.dp)
                        .background(color = custodyColor, shape = CircleShape)
                        .padding(top = 2.dp)
                )
            }

            // Event indicators
            if (events.isNotEmpty()) {
                val momEvents = events.filter { it.parentOwner == "mom" }
                val dadEvents = events.filter { it.parentOwner == "dad" }

                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (momEvents.isNotEmpty()) {
                        EventIndicatorDot(
                            color = CoParentlyColors.MomPink,
                            isToday = isToday
                        )
                    }
                    if (dadEvents.isNotEmpty()) {
                        EventIndicatorDot(
                            color = CoParentlyColors.DadBlue,
                            isToday = isToday
                        )
                    }
                    if (momEvents.isEmpty() && dadEvents.isEmpty() && events.isNotEmpty()) {
                        EventIndicatorDot(
                            color = MaterialTheme.colorScheme.tertiary,
                            isToday = isToday
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventIndicatorDot(
    color: Color,
    isToday: Boolean = false
) {
    val size by animateFloatAsState(
        targetValue = if (isToday) 8f else 6f,
        animationSpec = tween(200),
        label = "dotSize"
    )

    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color = color, shape = CircleShape)
    )
}
