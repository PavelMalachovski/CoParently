package com.coparently.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Conversation
import com.coparently.app.presentation.common.animations.AnimatedEmptyState
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNavigateToPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val partnerId by viewModel.partnerId.collectAsState()

    // Chat needs a co-parent: if paired, open (or create) the conversation with them;
    // if not, send the user to pairing instead of silently doing nothing.
    val startChat: () -> Unit = {
        if (partnerId.isNullOrEmpty()) {
            onNavigateToPairing()
        } else {
            viewModel.startConversationWithPartner(onOpened = onConversationClick)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversations_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.nav_settings)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            // Only show the FAB when there are conversations; the empty state carries
            // its own primary action, so a second entry point would be redundant.
            if (conversations.isNotEmpty()) {
                FloatingActionButton(onClick = startChat) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_new_conversation))
                }
            }
        }
    ) { padding ->
        if (conversations.isEmpty()) {
            // Issue 8.2: Empty state for conversations
            AnimatedEmptyState(
                icon = Icons.Default.Chat,
                title = stringResource(R.string.chat_empty_title),
                description = stringResource(R.string.chat_empty_description),
                actionText = stringResource(R.string.chat_new_conversation),
                onActionClick = startChat
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(
                    items = conversations,
                    key = { conversation -> conversation.id }
                ) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        onClick = { onConversationClick(conversation.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    val timeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

    ListItem(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                conversation.lastMessage?.let { msg ->
                    Text(
                        text = msg.timestamp.format(timeFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = conversation.lastMessage?.content ?: stringResource(R.string.chat_no_messages),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (conversation.unreadCount > 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (conversation.unreadCount > 0) MaterialTheme.typography.bodyLarge.color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (conversation.unreadCount > 0) {
                BadgedBox(badge = { Badge { Text(conversation.unreadCount.toString()) } }) {
                    // Empty content for badge anchor
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
