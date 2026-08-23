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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PriorityHigh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.domain.expenses.CurrencyBalance
import com.coparently.app.domain.home.WeekEntry
import com.coparently.app.presentation.calendar.components.DayAgendaCard
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
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
 * @param onOpenContacts Opens the contacts list
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
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onOpenExpenses: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
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
        when (val state = uiState) {
            HomeUiState.AskForCoParent -> PairingInvitation(
                onNavigateToPairing = onNavigateToPairing,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )

            is HomeUiState.Dashboard -> Dashboard(
                state = state,
                parentNames = parentNames,
                contentPadding = padding,
                onOpenEvent = onOpenEvent,
                onOpenChangeRequests = onOpenChangeRequests,
                onOpenContacts = onOpenContacts,
                onOpenExpenses = onOpenExpenses,
                onOpenChat = onOpenChat
            )
        }
    }
}

/**
 * The whole unpaired page: a short explanation and one button.
 *
 * Everything else the dashboard shows depends on there being a second parent — there is no
 * handover without one, no balance to settle, and no changes for them to have made — so the
 * page says the one thing that would fill the rest instead of arranging hollow shells around it.
 *
 * @param onNavigateToPairing Opens the pairing screen
 * @param modifier Modifier applied to the page
 */
@Composable
private fun PairingInvitation(
    onNavigateToPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.home_pairing_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onNavigateToPairing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_pairing_cta))
        }
    }
}

/**
 * The paired dashboard, in spec §3's order.
 *
 * @param state Everything the page draws
 * @param parentNames Resolves a slot to that parent's name
 * @param contentPadding The scaffold's own insets
 * @param onOpenEvent Opens an event by id
 * @param onOpenChangeRequests Opens the change-request inbox
 * @param onOpenContacts Opens the contacts list
 * @param onOpenExpenses Switches to the Expenses tab
 * @param onOpenChat Switches to the Chat tab
 */
@Composable
// One callback per navigation target; the body is one linear column of sections, so splitting
// it would only move the length into a second file.
@Suppress("LongParameterList", "LongMethod")
private fun Dashboard(
    state: HomeUiState.Dashboard,
    parentNames: ParentNames,
    contentPadding: PaddingValues,
    onOpenEvent: (String) -> Unit,
    onOpenChangeRequests: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenExpenses: () -> Unit,
    onOpenChat: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        state.nextHandover?.let { handover ->
            item {
                HandoverHero(
                    info = handover,
                    parentNames = parentNames,
                    onConfirm = onOpenChangeRequests
                )
            }
        }

        // Today's agenda — the day card that used to sit under the calendar's month grid,
        // moved here so the grid fills its screen. Same composable, so the two surfaces can
        // never drift into different ideas of what a day looks like: date, whose custody day
        // it is, and the whole day's events (a 9:00 appointment is still part of today at
        // 9:05, which is why this is not just the week's first rows repeated).
        item {
            DayAgendaCard(
                date = state.today.date,
                events = state.today.events,
                custody = state.today.dayParent,
                parentNames = parentNames,
                onEventClick = onOpenEvent
            )
        }

        // The week leads, per spec §3: it is what a separated parent opens the app to see, and
        // it is where the timeline rail already sat.
        item { SectionHeader(stringResource(R.string.home_section_this_week)) }
        if (state.week.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.home_week_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            itemsIndexed(
                items = state.week,
                // Recurring occurrences share the master event's id, so `HomeWeek` builds a key
                // that carries the occurrence's own start time; the id alone collides.
                key = { _, entry -> entry.key }
            ) { index, entry ->
                TimelineRow(
                    entry = entry,
                    parentNames = parentNames,
                    isLast = index == state.week.lastIndex,
                    onClick = { onOpenEvent(entry.event.id) }
                )
            }
        }

        item {
            // Deliberately `partner?.name` and not `parentNames`, which is what the hero and
            // the timeline above use. The two answer different questions. This header names
            // a *person* - the account this one is paired with - and that identity is known
            // as soon as pairing resolves. The hero names whoever holds a *slot*, and on a
            // pair whose two parents still share slot 1 nobody holds the other one, so it
            // says "Parent" until the backfill separates them.
            //
            // So a legacy pair reads "Olya changed" here and "Today with Parent" above, and
            // that is correct rather than an inconsistency to iron out: degrading this to
            // "Parent" would throw away a fact we hold, and resolving the hero from the
            // partner's name would assert a slot nobody has stored - the guess this whole
            // branch exists to remove.
            SectionHeader(
                state.partner?.name?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.home_section_partner_changed, it) }
                    ?: stringResource(R.string.home_section_recent_changes)
            )
        }

        if (state.recentChanges.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.home_recent_empty_paired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            item {
                ActivityGroup(
                    items = state.recentChanges,
                    onOpenChangeRequests = onOpenChangeRequests,
                    onOpenEvent = onOpenEvent
                )
            }
        }

        item {
            // Item 16: the numbers worth finding in a hurry, behind one button. It sits here
            // rather than in the top bar because it is a destination, not a screen action, and
            // an unlabelled icon up there is exactly what the design refresh removed elsewhere.
            SectionGroup {
                SectionRow(
                    title = stringResource(R.string.home_contacts),
                    icon = Icons.Default.Contacts,
                    supporting = stringResource(R.string.home_contacts_supporting),
                    onClick = onOpenContacts,
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        item {
            // Last, as spec §3 asks. The unread tile travels with it rather than being
            // stranded alone at the top: the two are one row, and the Chat tab already
            // carries its own unread badge, so nothing is lost by it sitting here.
            Spacer(modifier = Modifier.size(4.dp))
            StatTiles(
                spend = state.monthSpend,
                balances = state.monthBalances,
                unreadCount = state.unreadCount,
                onOpenExpenses = onOpenExpenses,
                onOpenChat = onOpenChat
            )
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
 * One row of the child's week: a parent-coloured node on a vertical rail, with the event beside
 * it and an exclamation mark when the co-parent is expected.
 *
 * **The colour and the words name the same parent** — the one whose custody day the event falls
 * on, which is the question this row exists to answer. When no arrangement answers for that date
 * the row falls back to the event's own owner rather than going colourless: a rail of grey dots
 * says nothing, and the owner is a fact the app does hold. The words drop the "'s day" clause in
 * that case, because that is the part that would be a guess.
 *
 * @param entry The row
 * @param parentNames Resolves a slot to that parent's name
 * @param isLast Whether this is the final row, which drops the trailing connector
 * @param onClick Opens the event
 */
@Composable
private fun TimelineRow(
    entry: WeekEntry,
    parentNames: ParentNames,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val event = entry.event
    val dotSlot = entry.dayParent ?: event.parentOwner
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
                    .background(ParentColors.fill(dotSlot))
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The mark carries its own description rather than none: an unexplained glyph
                // is worse than no glyph for anyone not reading the screen, and what it means —
                // that the co-parent is expected — is not guessable from an exclamation mark.
                if (event.isImportant) {
                    Icon(
                        imageVector = Icons.Default.PriorityHigh,
                        contentDescription = stringResource(R.string.event_important_mark_description),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val timeLabel = event.startDateTime.format(timelineFormatter)
            Text(
                text = entry.dayParent
                    ?.let { stringResource(R.string.home_timeline_meta, timeLabel, parentNames.labelFor(it)) }
                    ?: timeLabel,
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
