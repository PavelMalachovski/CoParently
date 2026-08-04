package com.coparently.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
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
 * @param onOpenChangeRequest Opens the change-request inbox with the request for the given
 *   event id highlighted; tapping a change-request card in the thread calls this
 * @param viewModel Chat state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// One callback per navigation target this screen offers; the body is the thread scaffold, which
// only reads as one screen when it is written as one.
@Suppress("LongParameterList", "LongMethod")
fun ChatScreen(
    conversationId: String,
    onBack: (() -> Unit)? = null,
    draft: String = "",
    onRequestChangeForEvent: (String) -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onOpenChangeRequest: ((String) -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val upcomingEvents by viewModel.upcomingEvents.collectAsState()

    val conversation = conversations.find { it.id == conversationId }

    var showTemplates by remember { mutableStateOf(false) }
    var showEventPicker by remember { mutableStateOf(false) }

    // Seeded by the incoming draft (Expenses settle-up) and by message templates. rememberSaveable
    // so a rotation does not throw away a message the user is halfway through writing.
    var composerText by rememberSaveable(draft) { mutableStateOf(draft) }
    val composerFocus = remember { FocusRequester() }

    // Bumped only when something *seeds* the composer, so the refocus below fires on that and on
    // nothing else. Keying the effect on the text itself refocused on every keystroke and, after a
    // rotation, reopened a keyboard the user had deliberately dismissed.
    var composerSeeds by rememberSaveable { mutableStateOf(0) }

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
                    ChatThreadHeader(title = title, messages = messages, currentUserId = currentUserId)
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
                onEventLinkClick = onOpenChangeRequest,
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
                value = composerText,
                onValueChange = { composerText = it },
                onSendMessage = { content ->
                    viewModel.sendMessage(content)
                    composerText = ""
                },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = composerFocus
            )
        }
    }

    if (showTemplates) {
        MessageTemplatesBottomSheet(
            onTemplateSelected = { template ->
                // A template prepares the message; it does not send it. Sending on tap put three
                // identical placeholders-and-all messages into a real thread during the August
                // 2026 baseline run, because the send was invisible and read as a missed tap.
                composerText = template.content
                composerSeeds++
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

    // Fires only after the composer was seeded: put the cursor in the field and raise the
    // keyboard, so the text does not appear somewhere the user is not looking.
    LaunchedEffect(composerSeeds) {
        if (composerSeeds > 0) {
            composerFocus.requestFocus()
        }
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

/** Diameter of the co-parent avatar in the thread header. */
private val HEADER_AVATAR_SIZE = 36.dp

/**
 * The thread's identity line: who you are talking to, and whether what you sent actually left
 * the device.
 *
 * The status is derived from **your own** messages only. A co-parent's message that is still
 * SENDING is their problem to see, not yours, and folding it in here would make the header
 * flicker on every incoming message. The wording deliberately says "up to date" rather than the
 * mock's "synced just now": the app tracks no chat sync timestamp, and printing one it does not
 * have is exactly the kind of affordance this refresh removed elsewhere.
 *
 * @param title Conversation title — the co-parent's name once `ensureConversation` has run
 * @param messages Thread contents, newest last
 * @param currentUserId Whose messages count towards the status
 */
@Composable
private fun ChatThreadHeader(title: String, messages: List<Message>, currentUserId: String) {
    val mine = messages.filter { it.senderId == currentUserId }
    val status = when {
        mine.any { it.status == MessageSendStatus.ERROR } -> R.string.chat_failed_to_send
        mine.any { it.status == MessageSendStatus.SENDING } -> R.string.chat_sending_ellipsis
        else -> R.string.chat_header_synced
    }
    // Same three-way colour split as the sync row in Settings: errors shout, in-flight is muted,
    // settled is the tertiary accent.
    val statusColor: Color = when (status) {
        R.string.chat_failed_to_send -> MaterialTheme.colorScheme.error
        R.string.chat_sending_ellipsis -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(HEADER_AVATAR_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(status),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
        }
    }
}
