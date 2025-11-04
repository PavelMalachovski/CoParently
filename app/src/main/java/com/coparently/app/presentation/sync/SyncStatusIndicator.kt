package com.coparently.app.presentation.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coparently.app.data.sync.SyncStatus
import java.time.format.DateTimeFormatter

/**
 * Composable that displays the current synchronization status.
 * Shows progress, success, or error states with appropriate icons and messages.
 *
 * @param syncStatus Current sync status
 * @param modifier Modifier for customization
 */
@Composable
fun SyncStatusIndicator(
    syncStatus: SyncStatus,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = syncStatus !is SyncStatus.Idle,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        when (syncStatus) {
            is SyncStatus.Idle -> {
                // Not shown due to AnimatedVisibility
            }
            is SyncStatus.Syncing -> {
                SyncingIndicator(
                    progress = syncStatus.progress,
                    total = syncStatus.total,
                    modifier = modifier
                )
            }
            is SyncStatus.Success -> {
                SuccessIndicator(
                    lastSyncTime = syncStatus.lastSyncTime.format(
                        DateTimeFormatter.ofPattern("HH:mm:ss")
                    ),
                    modifier = modifier
                )
            }
            is SyncStatus.Error -> {
                ErrorIndicator(
                    errorMessage = syncStatus.message,
                    modifier = modifier
                )
            }
        }
    }
}

/**
 * Indicator shown during sync operation.
 */
@Composable
private fun SyncingIndicator(
    progress: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Syncing...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (total > 0) {
                    val progressPercent = (progress.toFloat() / total * 100).toInt()
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "$progressPercent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Indicator shown after successful sync.
 */
@Composable
private fun SuccessIndicator(
    lastSyncTime: String,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        isVisible = false
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Synced successfully",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Last sync: $lastSyncTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Indicator shown when sync fails.
 */
@Composable
private fun ErrorIndicator(
    errorMessage: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "Sync failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Compact sync status icon for app bars.
 */
@Composable
fun SyncStatusIcon(
    syncStatus: SyncStatus,
    onClick: (() -> Unit)? = null
) {
    val icon = when (syncStatus) {
        is SyncStatus.Idle -> Icons.Default.Refresh
        is SyncStatus.Syncing -> Icons.Default.Refresh
        is SyncStatus.Success -> Icons.Default.CheckCircle
        is SyncStatus.Error -> Icons.Default.Close
    }

    val tint = when (syncStatus) {
        is SyncStatus.Idle -> MaterialTheme.colorScheme.onSurface
        is SyncStatus.Syncing -> MaterialTheme.colorScheme.primary
        is SyncStatus.Success -> MaterialTheme.colorScheme.tertiary
        is SyncStatus.Error -> MaterialTheme.colorScheme.error
    }

    IconButton(
        onClick = { onClick?.invoke() },
        enabled = onClick != null
    ) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = when (syncStatus) {
                    is SyncStatus.Idle -> "Not synced"
                    is SyncStatus.Syncing -> "Syncing"
                    is SyncStatus.Success -> "Synced"
                    is SyncStatus.Error -> "Sync error"
                },
                tint = tint
            )
            if (syncStatus is SyncStatus.Syncing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Compact inline sync status badge.
 */
@Composable
fun SyncStatusBadge(
    syncStatus: SyncStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val (icon, color, text) = when (syncStatus) {
            is SyncStatus.Idle -> Triple(
                Icons.Default.Refresh,
                MaterialTheme.colorScheme.onSurfaceVariant,
                "Not synced"
            )
            is SyncStatus.Syncing -> Triple(
                Icons.Default.Refresh,
                MaterialTheme.colorScheme.primary,
                "Syncing..."
            )
            is SyncStatus.Success -> Triple(
                Icons.Default.CheckCircle,
                MaterialTheme.colorScheme.tertiary,
                "Synced"
            )
            is SyncStatus.Error -> Triple(
                Icons.Default.Close,
                MaterialTheme.colorScheme.error,
                "Error"
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

