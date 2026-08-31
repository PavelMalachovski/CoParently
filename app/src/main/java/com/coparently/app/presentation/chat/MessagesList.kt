package com.coparently.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.activity.ActivityEntityType
import com.coparently.app.domain.chat.ChatScrollPolicy
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageType
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
 * @param onEventLinkClick Handler for tapping a change-request card, given the linked event id,
 *   or null to render such cards inert (e.g. while the destination is not wired up yet)
 * @param modifier Modifier for the list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Already over detekt's LongMethod threshold before the Aug 2026 grouping/scroll work landed
// (the codebase's own baseline records that); explicit like the other screen composables that
// carry the same suppression rather than relying on a baseline entry keyed to an exact signature.
@Suppress("LongMethod")
@Suppress("LongParameterList") // one callback per action the thread offers
fun MessagesList(
    messages: List<Message>,
    currentUserId: String,
    canLoadEarlier: Boolean = false,
    onLoadEarlier: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onEventLinkClick: ((String) -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    onRetryFailed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val entries = remember(messages) { buildThread(messages) }

    // Survives a configuration change: the jump is about *opening* the thread, and a rotation
    // is not a re-open. The scroll position itself is restored by rememberLazyListState.
    var initialJumpDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(entries.size) {
        val target = ChatScrollPolicy.targetIndex(
            entryCount = entries.size,
            firstVisibleIndex = listState.firstVisibleItemIndex,
            lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            initialJumpDone = initialJumpDone
        )
        if (target != null) {
            // Instant on open — animating a flight past forty bubbles is its own defect —
            // and animated afterwards, when it is one new message sliding into view.
            if (initialJumpDone) {
                listState.animateScrollToItem(target)
            } else {
                listState.scrollToItem(target)
                initialJumpDone = true
            }
        } else if (entries.isNotEmpty()) {
            initialJumpDone = true
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
                // At index 0, above the oldest loaded message, and deliberately inside the list
                // rather than pinned above it (CQ-6). The reader has to be at the top to see it,
                // so after it grows the window they are still at the top and the newly loaded
                // messages appear directly below — no scroll anchor to restore.
                if (canLoadEarlier && onLoadEarlier != null) {
                    item(key = "load_earlier") {
                        TextButton(
                            onClick = onLoadEarlier,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.chat_load_earlier))
                        }
                    }
                }
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
                            endsGroup = entry.endsGroup,
                            onEventLinkClick = onEventLinkClick,
                            onOpenInbox = onOpenInbox,
                            onRetryFailed = onRetryFailed
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
 * The timestamp and delivery receipt sit *inside* the bubble, hugging its bottom-end corner —
 * the messenger convention, adopted in the August 2026 second pass. The first pass had moved
 * them below the bubble over a contrast worry, but that worry belonged to the old 10sp tail:
 * this meta renders at labelSmall in `onPrimary` at [META_ALPHA], which stays comfortably
 * readable on the filled primary bubble, and bottom-aligning it beside the last line keeps the
 * bubble no wider than its text plus one small trailer. The one state that stays *outside* is
 * a failed send: "failed to send" must shout in `error` red, and error-on-primary is exactly
 * the pairing that fails, so it renders as its own line under the bubble.
 *
 * @param message The message
 * @param isCurrentUser Whether this parent sent it, which sides and colours the bubble
 * @param startsGroup First of a run by the same sender — takes the larger top gap
 * @param endsGroup Last of a run — carries the tail corner
 * @param onEventLinkClick Handler for tapping a change-request card, given the linked event id,
 *   or null to render such cards inert
 * @param onOpenInbox Opens the change-request inbox with nothing highlighted — the tap target
 *   for a day-swap card, whose entity is a date and not an event id, or null to render it inert
 * @param onRetryFailed Re-sends everything still queued, from a tap on a failed message, or null
 *   to leave the failure notice inert
 */
@Composable
// Already over detekt's LongMethod threshold before this task added the clickable chevron (the
// codebase's own baseline records that); explicit like the other screen composables that carry
// the same suppression rather than relying on a baseline entry keyed to an exact signature.
@Suppress("LongMethod")
fun MessageItem(
    message: Message,
    isCurrentUser: Boolean,
    startsGroup: Boolean = true,
    endsGroup: Boolean = true,
    onEventLinkClick: ((String) -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    onRetryFailed: (() -> Unit)? = null
) {
    // A change-request card is the one bubble that goes somewhere. It carries the event id in
    // `attachments`; the inbox resolves that to the request itself.
    //
    // An announcement about an event or a change request goes to the same place, by the same id.
    // A day-swap announcement opens the same inbox unhighlighted — its entity is a date, which
    // the route's event-id argument would misread as a request that no longer exists. An
    // expense announcement stays inert: expenses have no route reachable from chat, and a
    // bubble that looks tappable and does nothing is worse than one that plainly is not.
    val activity = message.activity
    val onCardTap: (() -> Unit)? = when {
        message.messageType == MessageType.EVENT_LINK ->
            message.attachments.firstOrNull()?.let { id -> onEventLinkClick?.let { { it(id) } } }
        activity?.entityType == ActivityEntityType.EVENT ||
            activity?.entityType == ActivityEntityType.CHANGE_REQUEST ->
            onEventLinkClick?.let { { it(activity.entityId) } }
        activity?.entityType == ActivityEntityType.DAY_SWAP -> onOpenInbox
        else -> null
    }

    // The sentence is composed **here**, on the reading device, from the facts the sender put in
    // the payload — see `ActivityCardText`. `message.content` is the fallback a build that knows
    // nothing about `ACTIVITY` shows instead, and the fallback this one shows if the payload
    // could not be parsed.
    val bubbleText = activity
        ?.let { stringResource(ActivityCardText.resourceFor(it.kind), it.title) }
        ?: message.content

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (startsGroup) 8.dp else 0.dp),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        val openLabel = stringResource(R.string.chat_open_change_request)
        Row(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .clip(bubbleShape(isCurrentUser, startsGroup, endsGroup))
                .background(
                    if (isCurrentUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        // A step above the day pill's surfaceContainer, so a bubble and a
                        // separator never read as the same kind of surface.
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
                .then(
                    if (onCardTap != null) {
                        Modifier.clickable(
                            onClickLabel = openLabel,
                            role = Role.Button
                        ) { onCardTap() }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            // Bottom, not centre: the meta trailer sits beside the *last* line of a
            // multi-line message, hugging the bubble's bottom-end corner.
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = bubbleText,
                color = if (isCurrentUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (onCardTap != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(RECEIPT_ICON_SIZE * CHEVRON_SCALE),
                    tint = if (isCurrentUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            BubbleMeta(
                message = message,
                isCurrentUser = isCurrentUser,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // The one delivery state loud enough to leave the bubble: error red on the filled
        // primary background is the contrast pairing that fails, so a failed send says so
        // on the surface below instead.
        if (isCurrentUser && message.status == MessageSendStatus.ERROR) {
            // Tappable, because until the outbox existed this notice was a dead end: nothing in
            // the app ever retried a message whose write had failed, so it stayed undelivered
            // forever while the thread looked normal on this device.
            Row(
                modifier = Modifier
                    .padding(top = 2.dp, start = 4.dp, end = 4.dp)
                    .then(
                        if (onRetryFailed == null) {
                            Modifier
                        } else {
                            Modifier.clickable(onClick = onRetryFailed)
                        }
                    ),
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
                    text = if (onRetryFailed == null) {
                        stringResource(R.string.chat_failed_to_send)
                    } else {
                        stringResource(R.string.chat_failed_to_send_retry)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * The in-bubble trailer: sent time, and (on an outgoing message) the delivery receipt.
 *
 * On the primary bubble the trailer renders in `onPrimary` at [META_ALPHA] — present but a
 * step quieter than the message itself — and the READ receipt comes back to full `onPrimary`,
 * so "read" is visibly brighter than "delivered" the way messengers signal it. A failed
 * send shows its time here but keeps its receipt outside the bubble (see [MessageItem]).
 */
@Composable
private fun BubbleMeta(
    message: Message,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    val metaColor = if (isCurrentUser) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = META_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = formatSentAt(message.sentAtMillis, timeFormatter),
            style = MaterialTheme.typography.labelSmall,
            color = metaColor
        )
        if (isCurrentUser && message.status != MessageSendStatus.ERROR) {
            DeliveryReceipt(
                message = message,
                baseTint = metaColor,
                readTint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * Sending / sent / delivered / read, as an icon rather than a sentence.
 *
 * The state is real: `ChatViewModel` promotes a SENT message to DELIVERED or READ from the
 * conversation's per-user marks (`ChatReadState.statusFor`), so a delivered-but-unread message is
 * distinguishable from one the co-parent has actually opened.
 *
 * This `when` must handle every [MessageSendStatus] — the compiler rejects it as non-exhaustive
 * otherwise. [BubbleMeta] never calls this for ERROR (a failed send renders its own loud row
 * under the bubble), so that branch is a defensive no-frills glyph rather than a reachable state.
 *
 * Tints are taken as parameters because the receipt now lives *inside* the primary-filled
 * bubble: SENDING/SENT/DELIVERED render in [baseTint] (the quiet meta colour), READ in
 * [readTint] (full brightness) — never Mom-pink/Dad-blue, which are parent identity colours,
 * not status colours.
 */
@Composable
private fun DeliveryReceipt(message: Message, baseTint: Color, readTint: Color) {
    when (message.status) {
        MessageSendStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.chat_status_sending),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = baseTint
            )
        }
        MessageSendStatus.ERROR -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = stringResource(R.string.chat_status_error),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = baseTint
            )
        }
        MessageSendStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.chat_status_sent),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = baseTint
            )
        }
        MessageSendStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.chat_status_delivered),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = baseTint
            )
        }
        MessageSendStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.chat_status_read),
                modifier = Modifier.size(RECEIPT_ICON_SIZE),
                tint = readTint
            )
        }
    }
}

/**
 * Bubble corners.
 *
 * The two corners on the far side from the sender stay fully round. On the sender's own side
 * the corners between adjacent bubbles of one run are the mid radius, so the run stacks into
 * one visual block; the run's *last* bubble alone takes the tight tail corner, which is what
 * points the block at its sender. A lone message is both first and last, so it gets the round
 * top and the tail.
 *
 * @param isCurrentUser Which side the bubble sits on
 * @param startsGroup Whether this is the first bubble of a run by the same sender
 * @param endsGroup Whether this is the last bubble of a run — the tail's owner
 */
private fun bubbleShape(
    isCurrentUser: Boolean,
    startsGroup: Boolean,
    endsGroup: Boolean
): RoundedCornerShape {
    val innerTop = if (startsGroup) CORNER_ROUND else CORNER_MID
    val innerBottom = if (endsGroup) CORNER_TAIL else CORNER_MID
    return if (isCurrentUser) {
        RoundedCornerShape(
            topStart = CORNER_ROUND,
            topEnd = innerTop,
            bottomStart = CORNER_ROUND,
            bottomEnd = innerBottom
        )
    } else {
        RoundedCornerShape(
            topStart = innerTop,
            topEnd = CORNER_ROUND,
            bottomStart = innerBottom,
            bottomEnd = CORNER_ROUND
        )
    }
}

/** Fully rounded bubble corner. */
private val CORNER_ROUND = 18.dp

/** Sender-side corner between two adjacent bubbles of one run. */
private val CORNER_MID = 6.dp

/** The tail: the tight sender-side bottom corner on the last bubble of a run. */
private val CORNER_TAIL = 4.dp

/** Widest a bubble may grow before its text wraps. */
private val BUBBLE_MAX_WIDTH = 300.dp

/**
 * Opacity of the in-bubble meta (time and non-READ receipts) on an outgoing bubble.
 *
 * `onPrimary` is white on the indigo primary — a very high-contrast pairing — so even at this
 * alpha the labelSmall trailer clears readable contrast while sitting a step behind the
 * message text; READ receipts come back to full opacity so "read" is visibly brighter.
 */
private const val META_ALPHA = 0.78f

/** Size of the delivery-receipt glyph under an outgoing bubble. */
private val RECEIPT_ICON_SIZE = 13.dp

/** How much larger the change-request card's chevron is than the delivery-receipt glyph. */
private const val CHEVRON_SCALE = 1.4f

/** Brief pause after a manual refresh so the spinner does not flash out instantly. */
private const val REFRESH_SETTLE_MS = 500L
