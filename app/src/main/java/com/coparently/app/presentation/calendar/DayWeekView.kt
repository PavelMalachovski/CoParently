package com.coparently.app.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoParentlyColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Hourly view for day/week calendar views.
 * Shows activities scheduled by hour with the child.
 */
@Composable
fun DayWeekView(
    selectedDate: LocalDate,
    daysCount: Int,
    events: List<Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    onDateChange: (LocalDate) -> Unit,
    onEventClick: (String) -> Unit
) {
    val hours = (0..23).toList()
    val dates = remember(selectedDate, daysCount) {
        (0 until daysCount).map { selectedDate.plusDays(it.toLong()) }
    }

    // Handle swipe to change dates
    var totalDrag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selectedDate) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(totalDrag) > 200f) {
                            val daysToAdd = if (totalDrag > 0) -daysCount else daysCount
                            onDateChange(selectedDate.plusDays(daysToAdd.toLong()))
                        }
                        totalDrag = 0f
                    }
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // Hour labels column
            Column(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .fillMaxWidth()
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Time",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                hours.forEach { hour ->
                    HourLabel(hour = hour)
                }
            }

            // Days columns
            dates.forEach { date ->
                DayColumn(
                    date = date,
                    hours = hours,
                    events = events.filter {
                        it.startDateTime.toLocalDate() == date
                    },
                    custodySchedules = custodySchedules,
                    onEventClick = onEventClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun HourLabel(hour: Int) {
    Box(
        modifier = Modifier
            .height(60.dp)
            .fillMaxWidth()
            .padding(4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = String.format("%02d:00", hour),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    hours: List<Int>,
    events: List<Event>,
    custodySchedules: List<CustodyScheduleEntity>,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isToday = date == LocalDate.now()
    val custody = CustodyHelper.getCustodyForDate(date, custodySchedules)

    val backgroundColor = when {
        isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        custody == "mom" -> CoParentlyColors.MomPink.copy(alpha = 0.05f)
        custody == "dad" -> CoParentlyColors.DadBlue.copy(alpha = 0.05f)
        else -> MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier.background(backgroundColor)
    ) {
        // Day header
        Box(
            modifier = Modifier
                .height(40.dp)
                .fillMaxWidth()
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("EEE")),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Hour slots
        hours.forEach { hour ->
            HourSlot(
                hour = hour,
                date = date,
                events = events.filter { event ->
                    val eventHour = event.startDateTime.hour
                    eventHour == hour
                },
                custody = custody,
                onEventClick = onEventClick
            )
        }
    }
}

@Composable
private fun HourSlot(
    hour: Int,
    date: LocalDate,
    events: List<Event>,
    custody: String?,
    onEventClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .height(60.dp)
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable {
                events.firstOrNull()?.let { onEventClick(it.id) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (events.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(4.dp),
                horizontalAlignment = Alignment.Start
            ) {
                events.take(2).forEach { event ->
                    EventChip(
                        event = event,
                        custody = custody,
                        onClick = { onEventClick(event.id) }
                    )
                }
                if (events.size > 2) {
                    Text(
                        text = "+${events.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun EventChip(
    event: Event,
    custody: String?,
    onClick: () -> Unit
) {
    val backgroundColor = when (event.parentOwner) {
        "mom" -> CoParentlyColors.MomPink.copy(alpha = 0.7f)
        "dad" -> CoParentlyColors.DadBlue.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }

    val textColor = when (event.parentOwner) {
        "mom" -> CoParentlyColors.MomPinkDark
        "dad" -> CoParentlyColors.DadBlueDark
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = event.title,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1
        )
    }
}

