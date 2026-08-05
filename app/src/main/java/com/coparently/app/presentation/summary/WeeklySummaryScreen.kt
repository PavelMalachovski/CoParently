package com.coparently.app.presentation.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoPlanlyColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Days shown in the summary; must match [WeeklySummaryViewModel]'s window. */
private const val DAYS_IN_SUMMARY = 7L

/** How many pending requests are previewed inside the banner card. */
private const val MAX_PREVIEWED_REQUESTS = 3

private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val weekRangeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val eventTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val requestTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")

/**
 * Weekly summary dashboard: mutual activities of both parents for the next
 * seven days, with pending change requests surfaced at the top.
 */
@Suppress("LongMethod") // Compose screen: banner + seven day sections
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklySummaryScreen(
    onBack: () -> Unit,
    onEventClick: (String) -> Unit,
    onOpenChangeRequests: () -> Unit,
    viewModel: WeeklySummaryViewModel = hiltViewModel()
) {
    val eventsByDay by viewModel.eventsByDay.collectAsState()
    val pendingRequests by viewModel.pendingChangeRequests.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()

    val days = (0 until DAYS_IN_SUMMARY).map { viewModel.weekStart.plusDays(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Weekly Summary")
                        Text(
                            text = viewModel.weekStart.format(weekRangeFormatter) +
                                " – " + viewModel.weekEnd.format(weekRangeFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pendingRequests.isNotEmpty()) {
                item {
                    PendingRequestsCard(
                        requests = pendingRequests,
                        currentUserId = currentUserId,
                        onClick = onOpenChangeRequests
                    )
                }
            }

            days.forEach { day ->
                item(key = day.toString()) {
                    DayHeader(day = day)
                }
                val dayEvents = eventsByDay[day].orEmpty()
                if (dayEvents.isEmpty()) {
                    item {
                        Text(
                            text = "No shared activities",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                } else {
                    // Recurring occurrences share the master event id — key must
                    // include the start time to stay unique (project rule #4).
                    items(
                        dayEvents,
                        key = { "${it.id}_${it.startDateTime}" }
                    ) { event ->
                        SummaryEventRow(event = event, onClick = { onEventClick(event.id) })
                    }
                }
            }
        }
    }
}

/**
 * Banner card summarizing pending change requests; opens the inbox on tap.
 */
@Composable
private fun PendingRequestsCard(
    requests: List<ChangeRequest>,
    currentUserId: String,
    onClick: () -> Unit
) {
    val incoming = requests.count { it.requestedTo == currentUserId }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        incoming > 0 -> "$incoming change request${if (incoming > 1) "s" else ""} waiting for you"
                        else -> "${requests.size} pending change request${if (requests.size > 1) "s" else ""}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
            requests.take(MAX_PREVIEWED_REQUESTS).forEach { request ->
                Text(
                    text = "${request.eventTitle} → " +
                        request.proposedStartDateTime.format(requestTimeFormatter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate) {
    val isToday = day == LocalDate.now()
    Text(
        text = if (isToday) "Today · ${day.format(dayHeaderFormatter)}" else day.format(dayHeaderFormatter),
        style = MaterialTheme.typography.titleSmall,
        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * One event row: time, parent colour dot (slot 1 = pink, slot 2 = blue), title,
 * pickup confirmation marker.
 */
@Composable
private fun SummaryEventRow(
    event: Event,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (event.parentOwner == "mom") CoPlanlyColors.MomPink else CoPlanlyColors.DadBlue,
                        shape = CircleShape
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = event.startDateTime.format(eventTimeFormatter) +
                        (event.endDateTime?.let { " – " + it.format(eventTimeFormatter) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (event.pickupConfirmedBy != null) {
                Text(
                    text = "Pickup ✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
