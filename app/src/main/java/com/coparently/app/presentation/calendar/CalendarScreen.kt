package com.coparently.app.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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

/**
 * Main calendar screen showing monthly calendar view with events.
 */
@Composable
fun CalendarScreen(
    onEventClick: (String) -> Unit,
    onAddEventClick: () -> Unit,
    viewModel: EventViewModel = hiltViewModel()
) {
    val currentMonth = remember { YearMonth.now() }
    val startMonth = remember { currentMonth.minusMonths(12) }
    val endMonth = remember { currentMonth.plusMonths(12) }
    val firstDayOfWeek = remember { firstDayOfWeekFromLocale() }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = firstDayOfWeek
    )

    val events by viewModel.events.collectAsState()

    LaunchedEffect(calendarState.firstVisibleMonth) {
        val visibleMonth = calendarState.firstVisibleMonth.value.yearMonth
        val start = visibleMonth.atDay(1).atStartOfDay()
        val end = visibleMonth.atEndOfMonth().atTime(23, 59, 59)
        viewModel.loadEventsForDateRange(start, end)
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddEventClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add event"
                    )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                        onClick = { clickedDay ->
                            // Handle day click - show events or navigate
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun CalendarMonthHeader(yearMonth: YearMonth) {
    Text(
        text = "${yearMonth.month.name} ${yearMonth.year}",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun CalendarDayContent(
    day: CalendarDay,
    events: List<com.coparently.app.domain.model.Event>,
    onClick: (CalendarDay) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    day.position == DayPosition.MonthDate -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                }
            )

            // Show event indicators as small dots
            if (events.isNotEmpty()) {
                val momEvents = events.filter { it.parentOwner == "mom" }
                val dadEvents = events.filter { it.parentOwner == "dad" }

                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (momEvents.isNotEmpty()) {
                        EventIndicatorDot(color = CoParentlyColors.MomPink)
                    }
                    if (dadEvents.isNotEmpty()) {
                        EventIndicatorDot(color = CoParentlyColors.DadBlue)
                    }
                    if (momEvents.isEmpty() && dadEvents.isEmpty() && events.isNotEmpty()) {
                        EventIndicatorDot(color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventIndicatorDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color = color, shape = CircleShape)
    )
}

