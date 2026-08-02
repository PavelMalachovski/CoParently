package com.coparently.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.common.PillChip
import java.time.format.DateTimeFormatter

/**
 * A single conversation thread.
 *
 * Reworked by the August 2026 design review. The two unlabelled affordances it found — a
 * `swap_horiz` app-bar icon that meant "request change" here but something else on the
 * calendar, and a `+` in the composer that opened message *templates* rather than attachments —
 * are now labelled chips above the composer, so neither depends on the user guessing.
 *
 * @param conversationId Thread to show
 * @param onBack Up navigation, or null when the thread is the Chat tab itself and there is
 *   nothing to go back to
 * @param draft Pre-filled composer text (e.g. a settle-up message drafted on Expenses). Never
 *   sent automatically — a message to the co-parent is the user's to send.
 * @param onRequestChangeForEvent Starts a change request for the chosen event
 * @param onOpenSettings Opens settings; shown only when this thread *is* the tab, since the
 *   tab's own gear action would otherwise be lost
 * @param viewModel Chat state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // one callback per navigation target this screen offers
fun ChatScreen(
    conversationId: String,
    onBack: (() -> Unit)? = null,
    draft: String = "",
    onRequestChangeForEvent: (String) -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val upcomingEvents by viewModel.upcomingEvents.collectAsState()

    val conversation = conversations.find { it.id == conversationId }

    var showTemplates by remember { mutableStateOf(false) }
    var showEventPicker by remember { mutableStateOf(false) }

    // DisposableEffect, not LaunchedEffect: the "thread is open" signal that gates the
    // read/delivered marks must clear when this composable leaves — see
    // ChatViewModel.onThreadClosed. A configuration change disposes and recomposes this
    // screen while the same ChatViewModel instance survives, and onDispose is what closes
    // that window.
    DisposableEffect(conversationId) {
        viewModel.onThreadOpened(conversationId)
        onDispose { viewModel.onThreadClosed() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // A blank (not null) title means this row was mirrored locally before any
                    // successful `ensureConversation` set it — `?:` alone never catches that.
                    val title = conversation?.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.chat_title_fallback)
                    Text(title)
                },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.chat_back)
                            )
                        }
                    }
                },
                actions = {
                    onOpenSettings?.let { openSettings ->
                        IconButton(onClick = openSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MessagesList(
                messages = messages,
                currentUserId = currentUserId,
                onRefresh = {
                    viewModel.refreshThread()
                },
                modifier = Modifier.weight(1f)
            )

            // Labelled, above the composer — where a compose-time action belongs, and where
            // it can say what it does.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillChip(
                    label = stringResource(R.string.chat_request_change),
                    icon = Icons.Default.SwapHoriz,
                    onClick = { showEventPicker = true }
                )
                PillChip(
                    label = stringResource(R.string.chat_templates),
                    icon = Icons.Default.Bolt,
                    onClick = { showTemplates = true }
                )
            }

            MessageInput(
                initialText = draft,
                onSendMessage = { content ->
                    viewModel.sendMessage(content)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showTemplates) {
        MessageTemplatesBottomSheet(
            onTemplateSelected = { template ->
                // For now, just send the template content directly
                // In a real app, we might want to show a dialog to fill placeholders
                viewModel.sendTemplateMessage(template, template.content)
                showTemplates = false
            },
            onDismiss = { showTemplates = false }
        )
    }

    if (showEventPicker) {
        ChangeRequestEventPicker(
            events = upcomingEvents,
            onEventSelected = { event ->
                showEventPicker = false
                onRequestChangeForEvent(event.id)
            },
            onDismiss = { showEventPicker = false }
        )
    }
}

/**
 * Bottom sheet listing upcoming shared events; picking one starts a change request
 * (proposing a new time) for that event from within the chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeRequestEventPicker(
    events: List<Event>,
    onEventSelected: (Event) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_request_change_title),
                style = MaterialTheme.typography.titleMedium
            )
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_no_upcoming_events),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.chat_pick_event_to_reschedule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn {
                    items(events, key = { "${it.id}_${it.startDateTime}" }) { event ->
                        ListItem(
                            headlineContent = { Text(event.title) },
                            supportingContent = {
                                Text(event.startDateTime.format(dateFormatter))
                            },
                            modifier = Modifier.clickable { onEventSelected(event) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
