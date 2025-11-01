package com.coparently.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.presentation.sync.SyncState
import com.coparently.app.presentation.sync.SyncViewModel

/**
 * Settings screen for managing Google Calendar sync.
 */
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel()
) {
    // Note: Google Sign-In Intent should be launched from Activity
    // This requires integration with Activity context
    // For now, we show the UI components
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val isSyncEnabled by viewModel.isSyncEnabled.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Google Calendar Sync",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Sync enabled toggle
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable Sync",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isSyncEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.toggleSync(enabled)
                            },
                            enabled = isSignedIn || !isSyncEnabled
                        )
                    }

                    // Sign-in status
                    Text(
                        text = if (isSignedIn) "Signed in to Google" else "Not signed in",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSignedIn) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Sync status
                    when (syncState) {
                        is SyncState.Syncing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = syncState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        is com.coparently.app.presentation.sync.SyncState.Success -> {
                            Text(
                                text = "✓ ${syncState.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is com.coparently.app.presentation.sync.SyncState.Error -> {
                            Text(
                                text = "✗ ${syncState.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }

                    // Action buttons
                    Button(
                        onClick = { viewModel.syncFromGoogle() },
                        enabled = isSignedIn && isSyncEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 16.dp)
                    ) {
                        Text("Sync from Google Calendar")
                    }

                    if (isSignedIn) {
                        Button(
                            onClick = { viewModel.signOut() },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 8.dp)
                        ) {
                            Text("Sign Out")
                        }
                    }
                }
            }
        }
    }
}


