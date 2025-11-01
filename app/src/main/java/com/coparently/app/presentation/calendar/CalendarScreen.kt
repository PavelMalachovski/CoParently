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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kizitonwose.calendar.compose.VerticalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Main calendar screen showing calendar view with events.
 * Supports multiple view modes: Day, 3 Days, Week, Month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onEventClick: (String) -> Unit = {},
    onAddEventClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    eventViewModel: EventViewModel = hiltViewModel(),
    calendarViewModel: CalendarViewModel = hiltViewModel()
) {
    val events by eventViewModel.events.collectAsState()
    val custodySchedules by calendarViewModel.custodySchedules.collectAsState()
    val viewMode by calendarViewModel.viewMode.collectAsState()
    val selectedDate by calendarViewModel.selectedDate.collectAsState()

    val firstDayOfWeek = remember { firstDayOfWeekFromLocale() }
    val now = remember { YearMonth.now() }
    val startMonth = remember { now.minusMonths(12) }
    val endMonth = remember { now.plusMonths(12) }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = YearMonth.from(selectedDate),
        firstDayOfWeek = firstDayOfWeek
    )

    var showDatePicker by remember { mutableStateOf(false) }
    val currentYearMonth = calendarState.firstVisibleMonth.yearMonth
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentYearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli(),
        yearRange = IntRange(now.year - 5, now.year + 5)
    )
    val scope = rememberCoroutineScope()

    // Load events based on view mode
    LaunchedEffect(viewMode, selectedDate) {
        val start = when (viewMode) {
            CalendarViewMode.DAY -> selectedDate.atStartOfDay()
            CalendarViewMode.THREE_DAYS -> selectedDate.atStartOfDay()
            CalendarViewMode.WEEK -> {
                // Calculate first day of week (Monday = 1, Sunday = 7)
                val dayOfWeek = selectedDate.dayOfWeek.value
                val daysToSubtract = (dayOfWeek - 1).toLong()
                val firstDay = selectedDate.minusDays(daysToSubtract)
                firstDay.atStartOfDay()
            }
            CalendarViewMode.MONTH -> {
                val visibleMonth = YearMonth.from(selectedDate)
                visibleMonth.atDay(1).atStartOfDay()
            }
        }

        val end = when (viewMode) {
            CalendarViewMode.DAY -> selectedDate.atTime(23, 59, 59)
            CalendarViewMode.THREE_DAYS -> selectedDate.plusDays(2).atTime(23, 59, 59)
            CalendarViewMode.WEEK -> {
                val dayOfWeek = selectedDate.dayOfWeek.value
                val daysToAdd = (7 - dayOfWeek).toLong()
                val lastDay = selectedDate.plusDays(daysToAdd)
                lastDay.atTime(23, 59, 59)
            }
            CalendarViewMode.MONTH -> {
                val visibleMonth = YearMonth.from(selectedDate)
                visibleMonth.atEndOfMonth().atTime(23, 59, 59)
            }
        }

        eventViewModel.loadEventsForDateRange(start, end)
    }

    // Update calendar state when selected date changes (for month view)
    LaunchedEffect(selectedDate, viewMode) {
        if (viewMode == CalendarViewMode.MONTH) {
            val newMonth = YearMonth.from(selectedDate)
            val currentMonth = calendarState.firstVisibleMonth.yearMonth
            if (newMonth != currentMonth) {
                scope.launch {
                    try {
                        calendarState.animateScrollToMonth(newMonth)
                    } catch (e: Exception) {
                        // Handle scroll error gracefully
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = when (viewMode) {
                            CalendarViewMode.MONTH -> calendarState.firstVisibleMonth.yearMonth.toString()
                            else -> selectedDate.toString()
                        },
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                        }
                    ) { dateText ->
                        Text(
                            text = when (viewMode) {
                                CalendarViewMode.DAY -> selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
                                CalendarViewMode.THREE_DAYS -> "${selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} - ${selectedDate.plusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
                                CalendarViewMode.WEEK -> {
                                    val dayOfWeek = selectedDate.dayOfWeek.value
                                    val daysToSubtract = (dayOfWeek - 1).toLong()
                                    val firstDay = selectedDate.minusDays(daysToSubtract)
                                    val lastDay = firstDay.plusDays(6)
                                    "${firstDay.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} - ${lastDay.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
                                }
                                CalendarViewMode.MONTH -> "${YearMonth.from(selectedDate).month.name.lowercase().replaceFirstChar { it.uppercase() }} ${YearMonth.from(selectedDate).year}"
                            },
                            modifier = Modifier.clickable { showDatePicker = true }
                        )
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
            // View mode selector
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                CalendarViewMode.values().forEach { mode ->
                    SegmentedButton(
                        selected = viewMode == mode,
                        onClick = { calendarViewModel.setViewMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = CalendarViewMode.values().indexOf(mode),
                            count = CalendarViewMode.values().size
                        )
                    ) {
                        Text(
                            text = when (mode) {
                                CalendarViewMode.DAY -> "Day"
                                CalendarViewMode.THREE_DAYS -> "3 Days"
                                CalendarViewMode.WEEK -> "Week"
                                CalendarViewMode.MONTH -> "Month"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Today's custody indicator (only for month view)
            if (viewMode == CalendarViewMode.MONTH && custodySchedules.isNotEmpty()) {
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

            // Calendar content based on view mode
            AnimatedContent(
                targetState = viewMode,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                },
                modifier = Modifier.weight(1f)
            ) { mode ->
                when (mode) {
                    CalendarViewMode.DAY -> {
                        DayWeekView(
                            selectedDate = selectedDate,
                            daysCount = 1,
                            events = events,
                            custodySchedules = custodySchedules,
                            onDateChange = { calendarViewModel.setSelectedDate(it) },
                            onEventClick = onEventClick,
                            onAddEventClick = { date, hour ->
                                // Navigate to add event screen with preselected date and time
                                // For now, just call onAddEventClick
                                onAddEventClick()
                            }
                        )
                    }
                    CalendarViewMode.THREE_DAYS -> {
                        DayWeekView(
                            selectedDate = selectedDate,
                            daysCount = 3,
                            events = events,
                            custodySchedules = custodySchedules,
                            onDateChange = { calendarViewModel.setSelectedDate(it) },
                            onEventClick = onEventClick,
                            onAddEventClick = { date, hour ->
                                onAddEventClick()
                            }
                        )
                    }
                    CalendarViewMode.WEEK -> {
                        DayWeekView(
                            selectedDate = selectedDate,
                            daysCount = 7,
                            events = events,
                            custodySchedules = custodySchedules,
                            onDateChange = { calendarViewModel.setSelectedDate(it) },
                            onEventClick = onEventClick,
                            onAddEventClick = { date, hour ->
                                onAddEventClick()
                            }
                        )
                    }
                    CalendarViewMode.MONTH -> {
                        val visibleMonthYear = YearMonth.from(selectedDate)
                        var lastShownMonth by remember(visibleMonthYear) { mutableStateOf<YearMonth?>(null) }

                        VerticalCalendar(
                            state = calendarState,
                            modifier = Modifier.fillMaxSize(),
                            monthHeader = { month ->
                                // Show header only once per month
                                val monthYearMonth = month.yearMonth

                                // Show header only if it's the visible month and hasn't been shown yet
                                if (monthYearMonth == visibleMonthYear && lastShownMonth != monthYearMonth) {
                                    lastShownMonth = monthYearMonth
                                    CalendarMonthHeader(monthYearMonth)
                                } else {
                                    // Return empty box with minimal height to maintain layout
                                    Box(modifier = Modifier.height(0.dp))
                                }
                            },
                            dayContent = { day ->
                                // Show all days, but style differently for out-of-month days
                                CalendarDayContent(
                                    day = day,
                                    events = if (day.position == DayPosition.MonthDate) {
                                        events.filter { event ->
                                            event.startDateTime.toLocalDate() == day.date
                                        }
                                    } else {
                                        emptyList()
                                    },
                                    custodySchedules = if (day.position == DayPosition.MonthDate) {
                                        custodySchedules
                                    } else {
                                        emptyList()
                                    },
                                    onClick = { clickedDay ->
                                        calendarViewModel.setSelectedDate(clickedDay.date)
                                        if (viewMode != CalendarViewMode.DAY) {
                                            calendarViewModel.setViewMode(CalendarViewMode.DAY)
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // Date picker dialog for selecting month and year
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = LocalDate.ofInstant(
                                java.time.Instant.ofEpochMilli(millis),
                                ZoneId.systemDefault()
                            )
                            val selectedYearMonth = YearMonth.from(selectedDate)

                            calendarViewModel.setSelectedDate(selectedDate)
                            if (viewMode != CalendarViewMode.MONTH) {
                                calendarViewModel.setViewMode(CalendarViewMode.MONTH)
                            } else {
                                scope.launch {
                                    calendarState.animateScrollToMonth(selectedYearMonth)
                                }
                            }
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                Button(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
            colors = DatePickerDefaults.colors()
        ) {
            androidx.compose.material3.DatePicker(
                state = datePickerState,
                title = null,
                headline = null,
                showModeToggle = true
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
    ) { _: YearMonth ->
        // Month header is now in TopAppBar title
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
