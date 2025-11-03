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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onEventClick: (String) -> Unit,
    onAddEventClick: (LocalDate, Int) -> Unit = { _, _ -> }
) {
    val hours = (0..23).toList()
    val dates = remember(selectedDate, daysCount) {
        (0 until daysCount).map { selectedDate.plusDays(it.toLong()) }
    }

    // Handle swipe to change dates
    val totalDragState = remember { mutableFloatStateOf(0f) }
    var totalDrag by totalDragState

    val scrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 6 // Start at 6 AM for better UX
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selectedDate) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dragValue = totalDrag
                        if (kotlin.math.abs(dragValue) > 200f) {
                            val daysToAdd = if (dragValue > 0) -daysCount else daysCount
                            onDateChange(selectedDate.plusDays(daysToAdd.toLong()))
                        }
                        totalDrag = 0f
                    }
                ) { _, dragAmount ->
                    totalDrag = totalDrag + dragAmount
                }
            }
    ) {
        // Fixed header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Time",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            dates.forEach { date ->
                val isToday = date == LocalDate.now()
                val custody = CustodyHelper.getCustodyForDate(date, custodySchedules)
                val backgroundColor = when {
                    isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                    custody == "mom" -> CoParentlyColors.MomPink.copy(alpha = 0.05f)
                    custody == "dad" -> CoParentlyColors.DadBlue.copy(alpha = 0.05f)
                    else -> MaterialTheme.colorScheme.surface
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(backgroundColor)
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
                            text = date.format(DateTimeFormatter.ofPattern("MMM d")),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Scrollable content - use single LazyColumn with Row items for synchronization
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            items(hours.size) { hourIndex ->
                val hour = hours[hourIndex]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    // Hour label
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(60.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
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

                    // Day columns for this hour
                    dates.forEach { date ->
                        val dateEvents = events.filter {
                            it.startDateTime.toLocalDate() == date &&
                            it.startDateTime.hour == hour
                        }
                        val isToday = date == LocalDate.now()
                        val custody = CustodyHelper.getCustodyForDate(date, custodySchedules)
                        val backgroundColor = when {
                            isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                            custody == "mom" -> CoParentlyColors.MomPink.copy(alpha = 0.05f)
                            custody == "dad" -> CoParentlyColors.DadBlue.copy(alpha = 0.05f)
                            else -> MaterialTheme.colorScheme.surface
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                                .background(
                                    color = backgroundColor,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    if (dateEvents.isNotEmpty()) {
                                        onEventClick(dateEvents.first().id)
                                    } else {
                                        onAddEventClick(date, hour)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (dateEvents.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.padding(4.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    dateEvents.take(2).forEach { event ->
                                        EventChip(
                                            event = event,
                                            custody = custody,
                                            onClick = { onEventClick(event.id) }
                                        )
                                    }
                                    if (dateEvents.size > 2) {
                                        Text(
                                            text = "+${dateEvents.size - 2}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
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

