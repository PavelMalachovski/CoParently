package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.ParentColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tint strength of the inline banners above the grid. */
private const val BANNER_TINT_ALPHA = 0.14f

/**
 * Pending change requests, as a labelled row above the grid.
 *
 * Replaces a badged `swap_horiz` icon in the header — an unlabelled glyph that also appeared in
 * chat meaning something adjacent but different, and whose badge said only "1" with no hint of
 * what one of. A banner can say what is waiting and offer the action inline.
 *
 * @param pendingCount Number of pending incoming requests; the caller hides this at zero
 * @param onReview Opens the change-requests inbox
 * @param modifier Modifier for the banner
 */
@Composable
fun ChangeRequestBanner(
    pendingCount: Int,
    onReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .clickable(onClick = onReview)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = pluralStringResource(
                R.plurals.calendar_change_requests_banner,
                pendingCount,
                pendingCount
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.calendar_change_requests_review),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * School vacation stated once for the whole month.
 *
 * In July and August every single cell used to carry a teal strip, which is per-day noise for a
 * month-level fact: the strip stopped distinguishing anything precisely when it was most
 * visible. One banner says the same thing and gives the grid its bottom edge back.
 *
 * @param label Vacation name, or a range description
 * @param modifier Modifier for the banner
 */
@Composable
fun VacationBanner(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CoPlanlyColors.VacationTint.copy(alpha = BANNER_TINT_ALPHA))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CoPlanlyColors.VacationTint)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Announces that the shared custody schedule changed under this device — last-write-wins with
 * no consent step, so the one thing that must not happen is the change landing silently. Shown
 * only for a change this device did not make itself; see
 * `CalendarViewModel.custodyChangeAnnouncement` for that decision.
 *
 * The closest shape in this file is [VacationBanner] — a tinted `Row` with a coloured dash and a
 * `Text` — copied here rather than invented anew. Unlike [VacationBanner] and
 * [ChangeRequestBanner], this banner reports something that already happened rather than
 * something waiting on the user, so it is the one banner in this file with something to
 * acknowledge: a plain trailing `IconButton`, not a second visual language for dismissal.
 *
 * @param byName The name of whoever changed it, already resolved by the caller. Resolution goes
 *   uid ([com.coparently.app.domain.custody.SharedCustody.lastModifiedBy]) to slot
 *   (`Parents.roleByUid`) to label (`parentLabel`) — a composable cannot do that lookup itself
 *   without also being handed the raw uid, which would let it guess a slot instead of reporting
 *   "unknown parent" for one that matches neither. This parameter is that already-safe result.
 * @param onDismiss Persists the dismissal, keyed by the caller on the change's own
 *   `lastModifiedAt` so a later change is announced again.
 * @param modifier Modifier for the banner
 */
@Composable
fun CustodyChangedBanner(byName: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = BANNER_TINT_ALPHA))
            .padding(start = 11.dp, top = 5.dp, bottom = 5.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.secondary)
        )
        Text(
            text = stringResource(R.string.calendar_custody_changed_by, byName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.common_dismiss),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * The selected day's events, listed under the month grid.
 *
 * This is the other half of replacing event chips with dots: the dots say *how many*, and this
 * says *what*. Before, a cell showed the first event's title at roughly 9sp and hid the rest
 * behind "+N", so the only way to read a busy day was to leave the month view entirely.
 *
 * @param date The selected day
 * @param events That day's events, in start order
 * @param custody The day's custody slot, or null when no custody model applies
 * @param parentNames Resolves a slot to that parent's name
 * @param onEventClick Opens an event
 * @param modifier Modifier for the card
 */
@Composable
// header, custody line and event rows are one card, not three; the parameters are the
// card's API surface, one per thing it displays
@Suppress("LongMethod", "LongParameterList")
fun DayAgendaCard(
    date: LocalDate,
    events: List<Event>,
    custody: String?,
    parentNames: ParentNames,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = remember(Locale.getDefault()) {
        DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = custody
                ?.let {
                    stringResource(
                        R.string.calendar_agenda_header_with_custody,
                        date.format(dateFormatter),
                        parentNames.labelFor(it)
                    )
                }
                ?: date.format(dateFormatter),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.calendar_agenda_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            events.forEach { event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEventClick(event.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ParentColors.fill(event.parentOwner))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = agendaTime(event, timeFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** "14:00–15:30", or just the start when the event has no end. */
@Composable
private fun agendaTime(event: Event, formatter: DateTimeFormatter): String {
    val start = event.startDateTime.format(formatter)
    val end = event.endDateTime?.format(formatter)
    return if (end != null) {
        stringResource(R.string.calendar_agenda_time_range, start, end)
    } else {
        start
    }
}
