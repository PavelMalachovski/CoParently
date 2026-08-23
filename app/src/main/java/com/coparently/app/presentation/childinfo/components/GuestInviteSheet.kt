package com.coparently.app.presentation.childinfo.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.R
import com.coparently.app.domain.guests.GuestAccessDuration
import com.coparently.app.domain.guests.GuestInviteUri
import com.coparently.app.presentation.childinfo.GuestInviteState
import com.coparently.app.presentation.common.PillChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Offering somebody read access to one child's record.
 *
 * Two steps, in this order and not the other: choose how long, then get a code. The length
 * is not an afterthought a parent can leave on a default they never read — it is the only
 * thing standing between a weekend visit and permanent access to a child's medical record,
 * so it is chosen before the code exists and cannot be changed after, since by then the
 * invitation document already carries it.
 *
 * The sheet says what the guest will and will not see. "No affordance may promise a feature
 * that doesn't exist" cuts both ways: an access grant must not be quieter about its scope
 * than the person accepting it would expect.
 *
 * @param state What to show — see [GuestInviteState].
 * @param onChooseDuration Picks a different length; ignored once a code exists.
 * @param onCreate Mints the invitation.
 * @param onShare Hands the guest link to the system share sheet.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GuestInviteSheet(
    state: GuestInviteState,
    onChooseDuration: (GuestAccessDuration) -> Unit,
    onCreate: () -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.isOpen) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.guest_invite_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.guest_invite_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val invite = state.invite
            if (invite == null) {
                DurationChoice(state = state, onChooseDuration = onChooseDuration)
                state.errorRes?.let { error ->
                    Text(
                        text = stringResource(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = onCreate,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(SPINNER))
                    } else {
                        Text(stringResource(R.string.guest_invite_create))
                    }
                }
            } else {
                MintedCode(
                    code = invite.code,
                    grantEndsAtMillis = invite.grantExpiresAtMillis,
                    onShare = onShare
                )
            }
        }
    }
}

/** The three lengths, as chips. The chosen one is filled; the rest are outlined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationChoice(
    state: GuestInviteState,
    onChooseDuration: (GuestAccessDuration) -> Unit
) {
    Text(
        text = stringResource(R.string.guest_invite_how_long),
        style = MaterialTheme.typography.titleSmall
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuestAccessDuration.entries.forEach { choice ->
            val selected = choice == state.duration
            PillChip(
                label = stringResource(choice.labelRes()),
                container = if (selected) MaterialTheme.colorScheme.secondaryContainer else null,
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = { onChooseDuration(choice) }
            )
        }
    }
}

/** The code, when it ends, and the one way to send it. */
@Composable
private fun MintedCode(code: String, grantEndsAtMillis: Long, onShare: (String) -> Unit) {
    val ends = remember(grantEndsAtMillis) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .format(Instant.ofEpochMilli(grantEndsAtMillis).atZone(ZoneId.systemDefault()))
    }
    Text(
        text = code,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = CODE_TRACKING
    )
    Text(
        text = stringResource(R.string.guest_invite_ends_on, ends),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(
        onClick = { onShare(GuestInviteUri.build(code)) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Share, contentDescription = null)
        Text(
            text = stringResource(R.string.guest_invite_share),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** The label for one length of access. */
@StringRes
private fun GuestAccessDuration.labelRes(): Int = when (this) {
    GuestAccessDuration.WEEK -> R.string.guest_invite_duration_week
    GuestAccessDuration.MONTH -> R.string.guest_invite_duration_month
    GuestAccessDuration.QUARTER -> R.string.guest_invite_duration_quarter
}

/** Matches the height of the button label it replaces, so the button does not resize. */
private val SPINNER = 20.dp

/** Wide enough that six characters read as six characters when spoken over the phone. */
private val CODE_TRACKING = 8.sp
