package com.coparently.app.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.expenses.CurrencyBalance
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.rememberParentNames
import com.coparently.app.presentation.theme.ParentColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

private val activityFormatter = DateTimeFormatter.ofPattern("d MMM · HH:mm")
private val timelineFormatter = DateTimeFormatter.ofPattern("EEE d · HH:mm")
private val handoverDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
private val todayFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

/** Strength of the parent-hue wash behind the handover hero. */
private const val HERO_TINT_ALPHA = 0.16f

/** Below this a balance is settled — matches the Expenses screen, so the two never disagree. */
private const val SETTLED_EPSILON = 0.01

/**
 * Home dashboard — the first screen. At-a-glance co-parenting state: the next
 * custody handover, this month's spend and unread messages, this week's events,
 * and the recent changes the co-parent made.
 *
 * Layout follows the August 2026 design refresh: the handover is a hero card carrying its own
 * next action, the two stat tiles are deep links rather than dead-end numbers, and the feeds
 * below drop the card-per-row chrome in favour of a timeline rail and one grouped list.
 *
 * @param onOpenEvent Opens an event by id
 * @param onOpenChangeRequests Opens the change-request inbox
 * @param onOpenWeeklySummary Opens the weekly summary dashboard
 * @param onOpenSettings Opens settings
 * @param onNavigateToPairing Opens the pairing screen
 * @param onOpenExpenses Switches to the Expenses tab — the spend tile's deep link
 * @param onOpenChat Switches to the Chat tab — the unread tile's deep link
 * @param viewModel Screen state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// One callback per navigation target this dashboard links to; the body is one linear column of
// sections, so splitting it would only move the length into a second file.
@Suppress("LongParameterList", "LongMethod")
fun HomeScreen(
    onOpenEvent: (String) -> Unit,
    onOpenChangeRequests: () -> Unit,
    onOpenWeeklySummary: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onOpenExpenses: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val recentChanges by viewModel.recentChanges.collectAsState()
    val paired by viewModel.paired.collectAsState()
    val partner by viewModel.partner.collectAsState()
    val nextHandover by viewModel.nextHandover.collectAsState()
    val upcomingEvents by viewModel.upcomingEvents.collectAsState()
    val monthSpend by viewModel.monthSpend.collectAsState()
    val monthBalances by viewModel.monthBalances.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val parentNames = rememberParentNames(viewModel.parents.collectAsState().value)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = LocalDate.now().format(todayFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Pairing is where most value comes from — lead with a CTA when unpaired.
            if (!paired) {
                item { PairingCta(onNavigateToPairing) }
            }

            nextHandover?.let { handover ->
                item {
                    HandoverHero(
                        info = handover,
                        parentNames = parentNames,
                        onConfirm = onOpenChangeRequests
                    )
                }
            }

            item {
                StatTiles(
                    spend = monthSpend,
                    balances = monthBalances,
                    unreadCount = unreadCount,
                    onOpenExpenses = onOpenExpenses,
                    onOpenChat = onOpenChat
                )
            }

            if (upcomingEvents.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_section_this_week)) }
                itemsIndexed(
                    items = upcomingEvents,
                    // Recurring occurrences share the master event's id, so the start time has
                    // to be part of the key or two occurrences collide.
                    key = { _, event -> "up_${event.id}_${event.startDateTime}" }
                ) { index, event ->
                    TimelineRow(
                        event = event,
                        parentNames = parentNames,
                        isLast = index == upcomingEvents.lastIndex,
                        onClick = { onOpenEvent(event.id) }
                    )
                }
            }

            item {
                SectionHeader(
                    partner?.name?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.home_section_partner_changed, it) }
                        ?: stringResource(R.string.home_section_recent_changes)
                )
            }

            if (recentChanges.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (paired) {
                                stringResource(R.string.home_recent_empty_paired)
                            } else {
                                stringResource(R.string.home_recent_empty_unpaired)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                item {
                    ActivityGroup(
                        items = recentChanges,
                        onOpenChangeRequests = onOpenChangeRequests,
                        onOpenEvent = onOpenEvent
                    )
                }
            }

            item {
                // Kept here deliberately. The refresh removed the unlabelled `view_list`
                // action from the calendar header, so this is now the weekly summary's single
                // entry point rather than one of two competing ones.
                Spacer(modifier = Modifier.size(4.dp))
                OutlinedButton(
                    onClick = onOpenWeeklySummary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.home_weekly_summary))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun PairingCta(onNavigateToPairing: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.home_pairing_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onNavigateToPairing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_pairing_cta))
            }
        }
    }
}

/**
 * The handover hero: who has the child now, when it changes hands, and the one action that
 * belongs to that fact.
 *
 * The wash runs from the current parent's hue into the next parent's, so the card itself shows
 * the direction of the handover before a word is read.
 *
 * @param info Next handover
 * @param onConfirm Opens the change-request inbox, where a handover is actually acted on
 */
@Composable
@Suppress("LongMethod") // one card: gradient, headline, chips and action read as a single block
private fun HandoverHero(
    info: HandoverInfo,
    parentNames: ParentNames,
    onConfirm: () -> Unit
) {
    val fromColor = ParentColors.fill(info.fromParent)
    val toColor = ParentColors.fill(info.toParent)
    val headline = when (info.daysUntil) {
        0L -> stringResource(
            R.string.home_handover_hero_today,
            parentNames.labelFor(info.toParent)
        )
        1L -> stringResource(
            R.string.home_handover_hero_tomorrow,
            parentNames.labelFor(info.toParent)
        )
        else -> pluralStringResource(
            R.plurals.home_handover_hero_in_days,
            info.daysUntil.toInt(),
            parentNames.labelFor(info.toParent),
            info.daysUntil.toInt()
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            fromColor.copy(alpha = HERO_TINT_ALPHA),
                            toColor.copy(alpha = HERO_TINT_ALPHA)
                        )
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(fromColor)
                )
                Text(
                    text = stringResource(
                        R.string.home_handover_current,
                        parentNames.labelFor(info.fromParent)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 26.sp,
                    lineHeight = 32.sp
                ),
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(
                    label = info.date.format(handoverDateFormatter),
                    container = ParentColors.container(info.toParent, alpha = 0.2f),
                    contentColor = ParentColors.text(info.toParent)
                )
                PillChip(
                    label = stringResource(R.string.home_handover_review),
                    onClick = onConfirm
                )
            }
        }
    }
}

/**
 * The two dashboard tiles. Both are deep links — tapping the money opens Expenses, tapping the
 * unread count opens Chat.
 *
 * The unread tile disappears at zero rather than sitting there saying "0", which is what it
 * says most of the time; the spend tile then takes the full width.
 *
 * @param spend This month's total, per currency
 * @param balances This month's settle-up position, per currency
 * @param unreadCount Unread messages across all conversations
 * @param onOpenExpenses Deep link for the spend tile
 * @param onOpenChat Deep link for the unread tile
 */
@Composable
private fun StatTiles(
    spend: MonthSpend,
    balances: List<CurrencyBalance>,
    unreadCount: Int,
    onOpenExpenses: () -> Unit,
    onOpenChat: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Payments,
            value = spend.byCurrency.joinToString(" · ") { formatMoney(it.amount, it.currency) },
            caption = balanceCaption(balances),
            onClick = onOpenExpenses
        )
        if (unreadCount > 0) {
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.Chat,
                badge = unreadCount,
                value = pluralStringResource(R.plurals.home_stat_unread_count, unreadCount, unreadCount),
                caption = stringResource(R.string.home_stat_open_chat),
                onClick = onOpenChat
            )
        }
    }
}

/**
 * The settle-up line under the spend figure: "You are owed 29.85", "You owe 29.85", or
 * "All settled".
 *
 * Only balances whose split could be worked out are reported — while unpaired there is one
 * parent on record and a debt figure would be invented.
 *
 * A month mixing currencies can owe in one direction in CZK and the other in USD, and the app
 * does no FX conversion, so there is no honest single sentence for that. Rather than joining
 * amounts under whichever direction happened to come first, this reports the **largest** single
 * balance and lets the Expenses screen — one tap away, and where this tile links — lay out the
 * per-currency detail.
 */
@Composable
private fun balanceCaption(balances: List<CurrencyBalance>): String {
    val largest = balances
        .filter { it.balance.splitKnown && abs(it.balance.netForCurrentUser) >= SETTLED_EPSILON }
        .maxByOrNull { abs(it.balance.netForCurrentUser) }
        ?: return stringResource(R.string.home_stat_settled)

    val amount = formatMoney(abs(largest.balance.netForCurrentUser), largest.currency)
    return if (largest.balance.netForCurrentUser > 0) {
        stringResource(R.string.home_stat_owed_to_you, amount)
    } else {
        stringResource(R.string.home_stat_you_owe, amount)
    }
}

@Composable
@Suppress("LongParameterList") // one tile anatomy, expressed as one parameter list
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    caption: String,
    onClick: () -> Unit,
    badge: Int? = null
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (badge != null) {
                BadgedBox(badge = { Badge { Text(badge.toString()) } }) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One event on the "this week" timeline: a parent-coloured node on a vertical rail, with the
 * event beside it. Replaces the per-row Card, which made a three-item feed look like a stack
 * of unrelated panels.
 *
 * @param event The event
 * @param isLast Whether this is the final row, which drops the trailing connector
 * @param onClick Opens the event
 */
@Composable
private fun TimelineRow(
    event: Event,
    parentNames: ParentNames,
    isLast: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.width(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(ParentColors.fill(event.parentOwner))
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(2.dp)
                        .height(28.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Column(modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.home_timeline_meta,
                    event.startDateTime.format(timelineFormatter),
                    parentNames.labelFor(event.parentOwner)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The co-parent's recent changes as one grouped list. A change *request* carries an inline
 * "Review" action, because it is the only entry in this feed that is waiting on the reader.
 *
 * @param items Activity entries, newest first
 * @param onOpenChangeRequests Opens the change-request inbox
 * @param onOpenEvent Opens an event by id
 */
@Composable
private fun ActivityGroup(
    items: List<ActivityItem>,
    onOpenChangeRequests: () -> Unit,
    onOpenEvent: (String) -> Unit
) {
    SectionGroup {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (item.isChangeRequest) onOpenChangeRequests() else onOpenEvent(item.eventId)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = item.kind.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(item.kind.labelRes(), item.title),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.timestamp.format(activityFormatter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.isChangeRequest) {
                    PillChip(
                        label = stringResource(R.string.home_activity_review),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onOpenChangeRequests
                    )
                }
            }
            if (index != items.lastIndex) Divider()
        }
    }
}

private fun formatMoney(amount: Double, currencyCode: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    runCatching { format.currency = Currency.getInstance(currencyCode) }
    return format.format(amount)
}

private fun ActivityKind.icon(): ImageVector = when (this) {
    ActivityKind.EVENT_CREATED -> Icons.Default.Add
    ActivityKind.EVENT_UPDATED -> Icons.Default.Edit
    ActivityKind.PICKUP_CONFIRMED -> Icons.Default.CheckCircle
    ActivityKind.CHANGE_REQUESTED -> Icons.Default.SwapHoriz
}

private fun ActivityKind.labelRes(): Int = when (this) {
    ActivityKind.EVENT_CREATED -> R.string.home_activity_event_created
    ActivityKind.EVENT_UPDATED -> R.string.home_activity_event_updated
    ActivityKind.PICKUP_CONFIRMED -> R.string.home_activity_pickup_confirmed
    ActivityKind.CHANGE_REQUESTED -> R.string.home_activity_change_requested
}
