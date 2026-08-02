package com.coparently.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * The conversation list, and the single entry point into a chat with the co-parent.
 *
 * The "is there a co-parent" decision belongs to the ViewModel, not here: at the moment of
 * a tap the pairing state may still be resolving, and treating that as "not paired" would
 * either bounce the user to pairing for an account that *is* paired or — as it used to —
 * do nothing at all. The screen just starts the action, shows progress while it resolves,
 * and renders whatever [ChatEvent] comes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNavigateToPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val isOpening by viewModel.isOpeningConversation.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Captured in composable scope: the collector below is not a composable, so it cannot
    // call stringResource itself.
    val noCoParentMessage = stringResource(R.string.chat_no_coparent)
    val noCoParentAction = stringResource(R.string.chat_no_coparent_action)
    val linkPendingMessage = stringResource(R.string.chat_coparent_link_pending)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ChatEvent.NoCoParent -> {
                    val result = snackbarHostState.showSnackbar(
                        message = noCoParentMessage,
                        actionLabel = noCoParentAction,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) onNavigateToPairing()
                }

                ChatEvent.CoParentLinkPending -> snackbarHostState.showSnackbar(linkPendingMessage)
            }
        }
    }

    val startChat: () -> Unit = {
        viewModel.startConversationWithPartner(onOpened = onConversationClick)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    if (isOpening) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_new_conversation))
                    }
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
