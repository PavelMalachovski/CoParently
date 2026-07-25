package com.coparently.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.format.DateTimeFormatter

private val activityFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")

/**
 * Home dashboard — the first screen. Surfaces the last few changes the co-parent
 * made (tap to open the event) and a shortcut into the weekly summary.
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Recent changes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (recentChanges.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        if (paired) {
                            Text(
                                text = "Nothing new from your co-parent yet. Changes they make will show up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            // Most of the app's value is gated on pairing, so make this a
                            // direct call-to-action instead of a dead notice.
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Pair with your co-parent to see the changes they make here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onNavigateToPairing,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Pair with your co-parent")
                                }
                            }
                        }
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
