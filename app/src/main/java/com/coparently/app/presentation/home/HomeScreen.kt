package com.coparently.app.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoPlanlyColors
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private val activityFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")
private val eventFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")
private val handoverDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

/**
 * Home dashboard — the first screen. At-a-glance co-parenting state: the next
 * custody handover, this month's spend and unread messages, the next few events,
 * and the recent changes the co-parent made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenEvent: (String) -> Unit,
    onOpenChangeRequests: () -> Unit,
    onOpenWeeklySummary: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToPairing: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val recentChanges by viewModel.recentChanges.collectAsState()
    val paired by viewModel.paired.collectAsState()
    val nextHandover by viewModel.nextHandover.collectAsState()
    val upcomingEvents by viewModel.upcomingEvents.collectAsState()
    val monthSpend by viewModel.monthSpend.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Overview") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Pairing is where most value comes from — lead with a CTA when unpaired.
            if (!paired) {
                item { PairingCta(onNavigateToPairing) }
            }

            nextHandover?.let { handover ->
                item { HandoverCard(handover) }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Payments,
                        label = "This month",
                        value = formatMoney(monthSpend.total, monthSpend.currency)
                    )
                    StatTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.Chat,
                        label = "Unread",
                        value = unreadCount.toString()
                    )
                }
            }

            if (upcomingEvents.isNotEmpty()) {
                item { SectionHeader("Upcoming") }
                items(upcomingEvents, key = { "up_${it.id}_${it.startDateTime}" }) { event ->
                    UpcomingEventRow(event = event, onClick = { onOpenEvent(event.id) })
                }
            }

            item { SectionHeader("Recent changes") }

            if (recentChanges.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (paired) {
                                "Nothing new from your co-parent yet. Changes they make will show up here."
                            } else {
                                "Once you pair, changes your co-parent makes will show up here."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(recentChanges, key = { it.id }) { item ->
                    ActivityRow(
                        item = item,
                        onClick = {
                            if (item.isChangeRequest) onOpenChangeRequests() else onOpenEvent(item.eventId)
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.size(4.dp))
                OutlinedButton(
                    onClick = onOpenWeeklySummary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("View weekly summary")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PairingCta(onNavigateToPairing: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pair with your co-parent to share the calendar, expenses and messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onNavigateToPairing, modifier = Modifier.fillMaxWidth()) {
                Text("Pair with your co-parent")
            }
        }
    }
}

@Composable
private fun HandoverCard(info: HandoverInfo) {
    val toColor = parentColor(info.toParent)
    val countdown = when (info.daysUntil) {
        0L -> "Handover today"
        1L -> "Handover tomorrow"
        else -> "Handover in ${info.daysUntil} days"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(toColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = toColor)
            }
            Column {
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${parentLabel(info.fromParent)} → ${parentLabel(info.toParent)} · " +
                        info.date.format(handoverDateFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpcomingEventRow(event: Event, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(parentColor(event.parentOwner))
                )
            },
            headlineContent = {
                Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    text = event.startDateTime.format(eventFormatter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                Icon(
                    imageVector = item.kind.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            headlineContent = { Text(item.title) },
            supportingContent = {
                Column {
                    Text(item.kind.label())
                    Text(
                        text = item.timestamp.format(activityFormatter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

private fun parentLabel(parent: String): String = when (parent) {
    "mom" -> "Mom"
    "dad" -> "Dad"
    else -> parent.replaceFirstChar { it.uppercase() }
}

private fun parentColor(parent: String): Color = when (parent) {
    "mom" -> CoPlanlyColors.MomPink
    "dad" -> CoPlanlyColors.DadBlue
    else -> CoPlanlyColors.MomPink
}

private fun formatMoney(amount: Double, currencyCode: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    runCatching { format.currency = Currency.getInstance(currencyCode) }
    return format.format(amount)
}

private fun ActivityKind.icon(): ImageVector = when (this) {
    ActivityKind.EVENT_CREATED -> Icons.Default.Add
    ActivityKind.EVENT_UPDATED -> Icons.Default.Edit
    ActivityKind.PICKUP_CONFIRMED -> Icons.Default.CheckCircle
    ActivityKind.CHANGE_REQUESTED -> Icons.Default.SwapHoriz
}

private fun ActivityKind.label(): String = when (this) {
    ActivityKind.EVENT_CREATED -> "Co-parent added this event"
    ActivityKind.EVENT_UPDATED -> "Co-parent updated this event"
    ActivityKind.PICKUP_CONFIRMED -> "Co-parent confirmed pickup"
    ActivityKind.CHANGE_REQUESTED -> "Co-parent requested a change"
}
