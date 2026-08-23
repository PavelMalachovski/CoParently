package com.coparently.app.presentation.changerequests

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.changerequests.ChangeRequestHighlight
import com.coparently.app.domain.custody.DayOverrideStatus
import com.coparently.app.domain.custody.DaySwap
import com.coparently.app.domain.custody.DaySwapInbox
import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.rememberParentNames
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val requestDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")

/**
 * Inbox of event change requests: incoming ones the user must respond to,
 * and outgoing ones the user sent to the co-parent.
 *
 * @param onBack Up navigation
 * @param onOpenEvent Opens the event a card refers to
 * @param linkedEventId Event id carried by a tapped chat card, or null for the plain inbox.
 *   When set, the newest request for that event ([ChangeRequestHighlight.forEvent]) is
 *   highlighted and scrolled into view; if the event has no request at all, a snackbar says so
 *   once the inbox has actually finished loading.
 * @param viewModel Change-request state
 */
@Suppress("LongMethod") // Compose screen: empty state + two list sections
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeRequestsScreen(
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    linkedEventId: String? = null,
    viewModel: ChangeRequestViewModel = hiltViewModel()
) {
    val requests by viewModel.changeRequests.collectAsState()
    val hasLoaded by viewModel.hasLoaded.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val incoming = requests.filter { it.requestedTo == currentUserId }
    val outgoing = requests.filter { it.requestedBy == currentUserId }

    val daySwaps by viewModel.daySwaps.collectAsState()
    val parents by viewModel.parents.collectAsState()
    val parentNames = rememberParentNames(parents)
    // Header plus one card per swap, or nothing at all. `indexInInbox` needs the count because
    // this section renders above the incoming one and every index below it shifts.
    val swapItemCount = if (daySwaps.isEmpty()) 0 else 1 + daySwaps.size

    val highlighted = remember(requests, linkedEventId) {
        linkedEventId?.let { ChangeRequestHighlight.forEvent(requests, it) }
    }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val missingMessage = stringResource(R.string.change_request_link_missing)

    // Arriving from a chat card: scroll the request into view, or say why there is nothing to
    // see. Gated on `hasLoaded`, not on `requests` being non-empty: `changeRequests` is seeded
    // with `initialValue = emptyList()` before Room's flow has emitted for real, and that
    // placeholder is indistinguishable from a genuinely empty result by list contents alone —
    // gating on emptiness fired the "already closed" snackbar on every cold arrival, before the
    // real data had a chance to load. `hasLoaded` (ChangeRequestViewModel) flips true on the
    // first real emission, whatever it is, so the check below only runs once that has happened;
    // a linked event that truly has no request still gets the snackbar, just not prematurely.
    // Also gated on `currentUserId`, which is populated by a separate, unordered coroutine
    // (`ChangeRequestViewModel.getCurrentUser()`) and is not sequenced against the requests flow
    // that drives `hasLoaded`. `incoming`/`outgoing` above are filtered on `currentUserId`, so if
    // the requests flow emits first, both sections would still be empty and the scroll index
    // would silently resolve to -1. Keyed on `currentUserId` too, this effect re-runs once that
    // settles — but `currentUserId` is set once and never changes again for the lifetime of the
    // screen, so the effect still runs exactly once per arrival: accepting or declining the
    // highlighted card afterwards changes `requests` but must not scroll the list again out from
    // under the user.
    LaunchedEffect(linkedEventId, hasLoaded, currentUserId) {
        if (linkedEventId == null || !hasLoaded || currentUserId.isEmpty()) return@LaunchedEffect
        val target = highlighted
        if (target == null) {
            snackbarHostState.showSnackbar(missingMessage)
        } else {
            val index = ChangeRequestHighlight.indexInInbox(
                incoming = incoming,
                outgoing = outgoing,
                requestId = target.id,
                precedingItems = swapItemCount
            )
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.change_request_inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.change_request_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (incoming.isEmpty() && outgoing.isEmpty() && daySwaps.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.change_request_empty_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.change_request_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Day swaps first: a pending swap is the most actionable thing the inbox can
                // hold — a date is about to arrive whether or not it is answered — and unlike a
                // change request it has no second home anywhere else in the app.
                if (daySwaps.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.day_swap_inbox_section)) }
                    items(daySwaps, key = { it.date.toString() }) { swap ->
                        DaySwapCard(
                            swap = swap,
                            currentUserId = currentUserId,
                            parentNames = parentNames,
                            onAccept = { viewModel.acceptSwap(swap.date) },
                            onDecline = { viewModel.declineSwap(swap.date) }
                        )
                    }
                }
                if (incoming.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.change_request_incoming)) }
                    items(incoming, key = { it.id }) { request ->
                        ChangeRequestCard(
                            request = request,
                            isIncoming = true,
                            onOpenEvent = onOpenEvent,
                            onAccept = { viewModel.accept(request.id) },
                            onDecline = { viewModel.decline(request.id) },
                            onCancel = {},
                            isHighlighted = request.id == highlighted?.id
                        )
                    }
                }
                if (outgoing.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.change_request_outgoing)) }
                    items(outgoing, key = { it.id }) { request ->
                        ChangeRequestCard(
                            request = request,
                            isIncoming = false,
                            onOpenEvent = onOpenEvent,
                            onAccept = {},
                            onDecline = {},
                            onCancel = { viewModel.cancel(request.id) },
                            isHighlighted = request.id == highlighted?.id
                        )
                    }
                }
            }
        }
    }
}

/**
 * One day swap in the inbox.
 *
 * Three states, and the difference between them is who is being asked. A swap the co-parent
 * offered carries Accept and Decline; one this parent offered carries neither, because
 * `DayOverrideTransition` and `firestore.rules` both refuse a parent deciding their own offer —
 * rendering the buttons anyway would promise an action the server rejects. A swap that has been
 * answered carries the answer, which is the whole reason an answered swap stays in this list.
 *
 * @param swap The day and where its offer stands.
 * @param currentUserId This device's own uid, for telling "offered to you" from "offered by you".
 * @param parentNames Resolves a uid or a slot to that person's name — never "Mom" or "Dad".
 * @param onAccept Takes the offer up.
 * @param onDecline Turns it down.
 */
@Composable
private fun DaySwapCard(
    swap: DaySwap,
    currentUserId: String,
    parentNames: ParentNames,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val awaitsMe = DaySwapInbox.awaitsAnswerFrom(swap, currentUserId)
    val offeredByMe = swap.override.requestedBy == currentUserId

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = swap.date.format(dateFormatter),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (offeredByMe) {
                    stringResource(
                        R.string.day_swap_inbox_offered_by_you,
                        parentNames.labelFor(swap.override.toParent)
                    )
                } else {
                    stringResource(
                        R.string.day_swap_inbox_offered_to_you,
                        parentNames.labelForUid(swap.override.requestedBy)
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            swap.override.note?.let { note ->
                Text(text = note, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(
                    when (swap.override.status) {
                        DayOverrideStatus.ACCEPTED -> R.string.day_swap_inbox_accepted
                        DayOverrideStatus.DECLINED -> R.string.day_swap_inbox_declined
                        DayOverrideStatus.PENDING -> R.string.day_swap_inbox_waiting
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (awaitsMe) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAccept) {
                        Text(stringResource(R.string.day_swap_accept))
                    }
                    TextButton(onClick = onDecline) {
                        Text(stringResource(R.string.day_swap_decline))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

/**
 * Maps the domain status to its display string resource.
 *
 * [ChangeRequestStatus.displayName] stays hardcoded English on the domain enum on purpose — the
 * domain layer has no `Context` and must not grow one — so the screen does the mapping itself.
 */
@StringRes
private fun statusLabel(status: ChangeRequestStatus): Int = when (status) {
    ChangeRequestStatus.PENDING -> R.string.change_request_status_pending
    ChangeRequestStatus.ACCEPTED -> R.string.change_request_status_accepted
    ChangeRequestStatus.DECLINED -> R.string.change_request_status_declined
    ChangeRequestStatus.CANCELLED -> R.string.change_request_status_cancelled
}

/**
 * One change request: event title, current -> proposed time, optional note,
 * status chip and the actions available for it.
 *
 * @param isHighlighted Whether this is the request a chat card linked to; tints the card
 *   container so it stands out once scrolled into view.
 */
@Suppress("LongMethod", "LongParameterList") // card layout + one callback per action
@Composable
fun ChangeRequestCard(
    request: ChangeRequest,
    isIncoming: Boolean,
    onOpenEvent: (String) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    isHighlighted: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpenEvent(request.eventId) },
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = request.eventTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(statusLabel(request.status))) }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.change_request_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = request.currentStartDateTime.format(requestDateFormatter),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = stringResource(R.string.change_request_proposed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = request.proposedStartDateTime.format(requestDateFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            request.note?.let { note ->
                Text(
                    text = stringResource(R.string.change_request_note_quoted, note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (request.status == ChangeRequestStatus.PENDING) {
                if (isIncoming) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.change_request_accept))
                        }
                        OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.change_request_decline))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.change_request_withdraw))
                    }
                }
            }
        }
    }
}
