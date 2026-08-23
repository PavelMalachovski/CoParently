package com.coparently.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
 * The Chat tab: the conversation list, and the single entry point into a chat with the co-parent.
 *
 * The "is there a co-parent" decision belongs to the ViewModel, not here: at the moment of
 * a tap the pairing state may still be resolving, and treating that as "not paired" would
 * either bounce the user to pairing for an account that *is* paired or — as it used to —
 * do nothing at all. The screen just starts the action, shows progress while it resolves,
 * and renders whatever [ChatEvent] comes back.
 *
 * With a **single** conversation — the shape of the product for essentially every user, since
 * there is one co-parent — the August 2026 design refresh renders that thread directly instead
 * of a one-row list you have to tap through. The list only appears once there is genuinely a
 * choice to make. The thread is composed in place rather than navigated to, deliberately:
 * navigating would leave the tab route behind, hide the bottom bar, and make Back bounce off a
 * list that immediately forwards again. Staying on the `conversations` route keeps the tab a tab.
 *
 * @param onConversationClick Opens a conversation by id (used when the list is shown)
 * @param onNavigateToPairing Sends an unpaired user to pairing
 * @param onOpenSettings Opens settings from the tab's gear action
 * @param draft Composer text carried in from elsewhere (e.g. a settle-up message)
 * @param onRequestChangeForEvent Starts a change request from the inlined thread, given the event.
 *   It no longer needs the conversation: `ActivityAnnouncer` resolves the pair's thread from the
 *   two uids, so the card reaches it whether the request was started here or from the calendar
 * @param onOpenChangeRequest Opens the change-request inbox with the request for the given
 *   event id highlighted; forwarded to the inlined [ChatScreen]
 * @param viewModel Chat state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// One callback per navigation target this tab offers; the body picks between the inline thread
// and the list, and both branches need the same collected state.
@Suppress("LongParameterList", "LongMethod")
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNavigateToPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    draft: String = "",
    onRequestChangeForEvent: (eventId: String) -> Unit = {},
    onOpenChangeRequest: ((String) -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val isOpening by viewModel.isOpeningConversation.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
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

    // One co-parent means one conversation, so the list would be a single row standing in front
    // of the only thread there is. Compose the thread here instead. Declared after the event
    // collector above so that stays mounted in both shapes.
    val onlyConversation = conversations.singleOrNull()
    if (onlyConversation != null) {
        ChatScreen(
            conversationId = onlyConversation.id,
            // No back arrow: this *is* the tab, there is nothing above it to return to.
            onBack = null,
            draft = draft,
            onRequestChangeForEvent = onRequestChangeForEvent,
            onOpenSettings = onOpenSettings,
            onOpenChangeRequest = onOpenChangeRequest
        )
        return
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
                        currentUserId = currentUserId,
                        onClick = { onConversationClick(conversation.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * One row of the conversation list.
 *
 * [currentUserId] is used to derive whether this conversation has unread activity, from
 * [Conversation.lastMessageAtMillis] versus this user's own [Conversation.lastReadAt] mark —
 * there is no stored per-message unread count any more, so this is a has-unread indicator
 * rather than a precise count.
 *
 * A blank [currentUserId] means the session has not resolved yet, not "a user with no mark":
 * looking a mark up under the empty string always misses, so an unguarded comparison
 * reported unread for every thread that has any activity at all. That was invisible while
 * nothing wrote `lastMessageAtMillis`; now that the conversation observer populates it, an
 * unresolved session would flash a badge on the first frame and clear it a moment later.
 */
@Composable
fun ConversationItem(
    conversation: Conversation,
    currentUserId: String,
    onClick: () -> Unit
) {
    val timeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    val hasUnread = currentUserId.isNotEmpty() &&
        (conversation.lastMessageAtMillis ?: 0L) > (conversation.lastReadAt[currentUserId] ?: 0L)

    ListItem(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // A blank (not null) title means this row was mirrored locally before any
                    // successful `ensureConversation` set it — show the fallback rather than
                    // an empty row.
                    text = conversation.title.ifBlank { stringResource(R.string.chat_title_fallback) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                conversation.lastMessage?.let { msg ->
                    Text(
                        text = formatSentAt(msg.sentAtMillis, timeFormatter),
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
                style = if (hasUnread) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (hasUnread) {
                    MaterialTheme.typography.bodyLarge.color
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailingContent = {
            if (hasUnread) {
                BadgedBox(badge = { Badge() }) {
                    // Empty content for badge anchor
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
