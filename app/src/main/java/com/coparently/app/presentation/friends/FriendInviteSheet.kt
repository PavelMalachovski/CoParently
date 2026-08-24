package com.coparently.app.presentation.friends

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.R
import com.coparently.app.domain.guests.GuestAccessDuration
import com.coparently.app.presentation.common.PillChip

/**
 * Offering somebody read access to the family calendar (item 16).
 *
 * Two steps, in this order and not the other, exactly as `GuestInviteSheet` argues: choose how
 * long, then get a code. The length is not an afterthought left on a default nobody read — it is
 * the only thing between "the grandmother has the children this weekend" and a stranger reading
 * the family's calendar forever, so it is chosen before the code exists and cannot be changed
 * after, since by then the invitation document already carries it.
 *
 * The sheet says what the friend will and will not see. An access grant must not be quieter about
 * its scope than the person accepting it would expect.
 *
 * @param state What to show — see [FriendInviteState].
 * @param onChooseDuration Picks a different length; ignored once a code exists.
 * @param onCreate Mints the invitation.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FriendInviteSheet(
    state: FriendInviteState,
    onChooseDuration: (GuestAccessDuration) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.friend_invite_action),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.friend_section_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val code = state.invite?.code
            if (code == null) {
                // Step one. The chips stay visible after the code is minted only as history —
                // see the `else` branch, which shows the code instead.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuestAccessDuration.entries.forEach { duration ->
                        PillChip(
                            label = stringResource(duration.labelRes()),
                            container = if (duration == state.duration) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                null
                            },
                            contentColor = if (duration == state.duration) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = { onChooseDuration(duration) }
                        )
                    }
                }
                Button(
                    onClick = onCreate,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.friend_invite_action))
                    }
                }
            } else {
                // Step two: the code itself, large enough to read out over the phone, which is
                // how these are actually passed on.
                Text(
                    text = stringResource(R.string.friend_invite_code_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp
                )
            }

            state.errorRes?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * The localized name of a grant length.
 *
 * Reuses the guest sheet's strings: they name a duration ("30 days"), not what it grants, so a
 * second set worded identically would be five more files to keep in step for no gain.
 */
@StringRes
private fun GuestAccessDuration.labelRes(): Int = when (this) {
    GuestAccessDuration.WEEK -> R.string.guest_invite_duration_week
    GuestAccessDuration.MONTH -> R.string.guest_invite_duration_month
    GuestAccessDuration.QUARTER -> R.string.guest_invite_duration_quarter
}
