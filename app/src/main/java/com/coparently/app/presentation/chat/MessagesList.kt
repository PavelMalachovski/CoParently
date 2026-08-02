package com.coparently.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.R
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.presentation.common.animations.AnimatedEmptyState
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * Messages list with pull-to-refresh functionality.
 * Issue 6.2: Added PullToRefreshBox to allow manual message reloading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesList(
    messages: List<Message>,
    currentUserId: String,
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // Auto-scroll only if user is already at the bottom (within last 2 items)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = messages.size

            // Check if user is already near the bottom (within last 2 items or at the end)
            val isNearBottom = lastVisibleIndex >= totalItems - 2 ||
                               firstVisibleIndex >= totalItems - 3

            if (isNearBottom) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (onRefresh != null) {
                isRefreshing = true
                scope.launch {
                    onRefresh()
                    kotlinx.coroutines.delay(500) // Small delay for better UX
                    isRefreshing = false
                }
            }
        },
        state = pullToRefreshState,
        modifier = modifier.fillMaxSize()
    ) {
        if (messages.isEmpty()) {
            // Issue 8.2: Empty state for messages
            AnimatedEmptyState(
                icon = Icons.Default.Message,
                title = stringResource(R.string.chat_messages_empty_title),
                description = stringResource(R.string.chat_messages_empty_description),
                actionText = null,
                onActionClick = null
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = messages,
                    key = { message -> message.id }
                ) { message ->
                    MessageItem(
                        message = message,
                        isCurrentUser = message.senderId == currentUserId
                    )
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    message: Message,
    isCurrentUser: Boolean
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isCurrentUser) 16.dp else 4.dp,
                        bottomEnd = if (isCurrentUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isCurrentUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = message.content,
                    color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = message.timestamp.format(timeFormatter),
                    color = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                )
            }
        }

        if (isCurrentUser) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (message.status) {
                    MessageSendStatus.SENDING -> {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = stringResource(R.string.chat_status_sending),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.chat_sending_ellipsis),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    MessageSendStatus.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.chat_status_error),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.chat_failed_to_send),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // This `when` must handle every MessageSendStatus: the Kotlin compiler
                    // rejects it as non-exhaustive otherwise (verified by building with the
                    // branches removed). One check for SENT, two (muted) for DELIVERED, two in
                    // colorScheme.primary for READ — never Mom-pink/Dad-blue, which are parent
                    // identity colours, not status colours.
                    MessageSendStatus.SENT -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.chat_status_sent),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    MessageSendStatus.DELIVERED -> {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = stringResource(R.string.chat_status_delivered),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    MessageSendStatus.READ -> {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = stringResource(R.string.chat_status_read),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
