package com.coparently.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.presentation.common.animations.AnimatedEmptyState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val daySeparatorFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** Consecutive messages from the same sender within this gap render as one group. */
private const val GROUPING_WINDOW_MS = 5 * 60 * 1000L

/**
 * The local day a message was sent on, in the reading device's zone.
 *
 * Day separators have to agree with the times printed under the bubbles, and those are rendered
 * locally by [formatSentAt] — so the day has to be derived the same way. Deriving it from the
 * instant alone would put a message sent at 23:30 in one zone under the wrong heading in another.
 */
private fun Message.localDate(): LocalDate =
    Instant.ofEpochMilli(sentAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * One entry in the rendered thread: either a day separator or a message with the grouping
 * information the renderer needs.
 */
private sealed interface ThreadEntry {

    /** A "Today" / "12 Aug 2026" pill above the first message of a day. */
    data class DayHeader(val date: LocalDate) : ThreadEntry

    /**
     * A message plus where it sits in its group.
     *
     * @property message The message
     * @property startsGroup First of a run by the same sender — gets the larger top gap
     * @property endsGroup Last of a run — the only one to show a timestamp and receipt
     */
    data class Bubble(
        val message: Message,
        val startsGroup: Boolean,
        val endsGroup: Boolean
    ) : ThreadEntry
}

/**
 * Splits a flat message list into day separators and grouped bubbles.
 *
 * Grouping and day separators were the August 2026 audit's finding on chat: every bubble carried
 * its own 10sp timestamp inside it, three messages sent in the same minute looked like three
 * unrelated events, and nothing marked where yesterday ended.
 *
 * @param messages Messages in ascending time order
 * @return Renderable entries in display order
 */
private fun buildThread(messages: List<Message>): List<ThreadEntry> {
    val entries = mutableListOf<ThreadEntry>()
    messages.forEachIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val day = message.localDate()

        if (previous == null || previous.localDate() != day) {
            entries += ThreadEntry.DayHeader(day)
        }

        val continuesFromPrevious = previous != null &&
            previous.senderId == message.senderId &&
            previous.localDate() == day &&
            message.sentAtMillis - previous.sentAtMillis <= GROUPING_WINDOW_MS
        val continuesIntoNext = next != null &&
            next.senderId == message.senderId &&
            next.localDate() == day &&
            next.sentAtMillis - message.sentAtMillis <= GROUPING_WINDOW_MS

        entries += ThreadEntry.Bubble(
            message = message,
            startsGroup = !continuesFromPrevious,
            endsGroup = !continuesIntoNext
        )
    }
    return entries
}

/**
 * The message thread, with day separators, grouped bubbles and delivery state.
 *
 * @param messages Messages in ascending time order, with [Message.status] already promoted to
 *   DELIVERED/READ by `ChatViewModel` via `ChatReadState.statusFor`
 * @param currentUserId Firebase uid of the signed-in parent, to side the bubbles
 * @param onRefresh Pull-to-refresh handler, or null to disable the gesture
 * @param modifier Modifier for the list
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
    val entries = remember(messages) { buildThread(messages) }

    // Auto-scroll only if user is already at the bottom (within last 2 items)
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = entries.size

            // Check if user is already near the bottom (within last 2 items or at the end)
            val isNearBottom = lastVisibleIndex >= totalItems - 2 ||
                firstVisibleIndex >= totalItems - 3

            if (isNearBottom) {
                listState.animateScrollToItem(entries.size - 1)
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
                    kotlinx.coroutines.delay(REFRESH_SETTLE_MS)
                    isRefreshing = false
                }
            }
        },
        state = pullToRefreshState,
        modifier = modifier.fillMaxSize()
    ) {
        if (messages.isEmpty()) {
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
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    count = entries.size,
                    key = { index ->
                        when (val entry = entries[index]) {
                            is ThreadEntry.DayHeader -> "day_${entry.date}"
                            is ThreadEntry.Bubble -> entry.message.id
                        }
                    }
                ) { index ->
                    when (val entry = entries[index]) {
                        is ThreadEntry.DayHeader -> DaySeparator(entry.date)
                        is ThreadEntry.Bubble -> MessageItem(
                            message = entry.message,
                            isCurrentUser = entry.message.senderId == currentUserId,
                            startsGroup = entry.startsGroup,
                            endsGroup = entry.endsGroup
                        )
                    }
                }
            }
        }
    }
}

/** Small centred pill marking where a new day begins in the thread. */
@Composable
private fun DaySeparator(date: LocalDate) {
    val label = when (date) {
        LocalDate.now() -> stringResource(R.string.chat_day_today)
        LocalDate.now().minusDays(1) -> stringResource(R.string.chat_day_yesterday)
        else -> date.format(daySeparatorFormatter)
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

/**
 * One message bubble.
 *
 * The timestamp and delivery receipt sit *below* the bubble rather than inside it: in the tail of
 * a 10sp label on a filled primary background they were borderline for contrast, and they pushed
 * every bubble wider than its text needed.
 *
 * @param message The message
 * @param isCurrentUser Whether this parent sent it, which sides and colours the bubble
 * @param startsGroup First of a run by the same sender — takes the larger top gap
 * @param endsGroup Last of a run — the only one showing a timestamp and receipt
 */
@Composable
fun MessageItem(
    message: Message,
    isCurrentUser: Boolean,
    startsGroup: Boolean = true,
    endsGroup: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (startsGroup) 8.dp else 0.dp),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .clip(bubbleShape(isCurrentUser, startsGroup))
                .background(
                    if (isCurrentUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                color = if (isCurrentUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (endsGroup) {
            Row(
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = formatSentAt(message.sentAtMillis, timeFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isCurrentUser) DeliveryReceipt(message)
            }
        }
    }
}

/**
 * Sending / failed / sent / delivered / read, as an icon rather than a sentence.
 *
 * The state is real: `ChatViewModel` promotes a SENT message to DELIVERED or READ from the
 * conversation's per-user marks (`ChatReadState.statusFor`), so a delivered-but-unread message is
 * distinguishable from one the co-parent has actually opened.
 *
 * This `when` must handle every [MessageSendStatus] — the compiler rejects it as non-exhaustive
 * otherwise. One check for SENT, two muted for DELIVERED, two in `colorScheme.primary` for READ —
 * never Mom-pink/Dad-blue, which are parent identity colours, not status colours.
 */
@Composable
private fun DeliveryReceipt(message: Message) {
    when (message.status) {
        MessageSendStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.chat_status_sending),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MessageSendStatus.ERROR -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.chat_status_error),
                    modifier = Modifier.size(RECEIPT_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.chat_failed_to_send),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        MessageSendStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.chat_status_sent),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MessageSendStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.chat_status_delivered),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MessageSendStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.chat_status_read),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Bubble corners.
 *
 * The two corners on the far side from the sender stay fully round. On the sender's own side the
 * bottom corner is always tight — that is the tail the original design already had — and the top
 * corner is only round on the first bubble of a run, so consecutive messages stack into one block
 * instead of a column of identical lozenges.
 *
 * @param isCurrentUser Which side the bubble sits on
 * @param startsGroup Whether this is the first bubble of a run by the same sender
 */
private fun bubbleShape(isCurrentUser: Boolean, startsGroup: Boolean): RoundedCornerShape {
    val innerTop = if (startsGroup) CORNER_ROUND else CORNER_TIGHT
    return if (isCurrentUser) {
        RoundedCornerShape(
            topStart = CORNER_ROUND,
            topEnd = innerTop,
            bottomStart = CORNER_ROUND,
            bottomEnd = CORNER_TIGHT
        )
    } else {
        RoundedCornerShape(
            topStart = innerTop,
            topEnd = CORNER_ROUND,
            bottomStart = CORNER_TIGHT,
            bottomEnd = CORNER_ROUND
        )
    }
}

/** Fully rounded bubble corner. */
private val CORNER_ROUND = 16.dp

/** Squared-off bubble corner on the sender's own side. */
private val CORNER_TIGHT = 4.dp

/** Widest a bubble may grow before its text wraps. */
private val BUBBLE_MAX_WIDTH = 280.dp

/** Size of the delivery-receipt glyph under an outgoing bubble. */
private val RECEIPT_ICON_SIZE = 13.dp

/** Brief pause after a manual refresh so the spinner does not flash out instantly. */
private const val REFRESH_SETTLE_MS = 500L
