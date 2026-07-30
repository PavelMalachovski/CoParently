package com.coparently.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Event
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    // Pre-filled composer text (e.g. a settle-up message drafted on Expenses). Never sent
    // automatically — a message to the co-parent is the user's to send.
    draft: String = "",
    onRequestChangeForEvent: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val upcomingEvents by viewModel.upcomingEvents.collectAsState()

    val conversation = conversations.find { it.id == conversationId }

    var showTemplates by remember { mutableStateOf(false) }
    var showEventPicker by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        viewModel.setConversationId(conversationId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(conversation?.title ?: "Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEventPicker = true }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Request change")
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
                    viewModel.refreshMessages()
                },
                modifier = Modifier.weight(1f)
            )

            MessageInput(
                initialText = draft,
                onSendMessage = { content ->
                    viewModel.sendMessage(content)
                },
                onAttachClick = { showTemplates = true },
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
                text = "Request a change",
                style = MaterialTheme.typography.titleMedium
            )
            if (events.isEmpty()) {
                Text(
                    text = "No upcoming events to propose a change for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Pick the event you'd like to reschedule:",
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
