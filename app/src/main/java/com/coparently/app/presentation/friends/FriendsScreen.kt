package com.coparently.app.presentation.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.friends.CalendarFriendGrant
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.theme.CoPlanlyColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Who outside the family can see the calendar, and the one control that changes it (item 16).
 *
 * A list and an invite row, in that order: the question a parent opens this screen with is
 * usually "who can see this", not "let somebody else in". Every grant states when it ends —
 * an access with no visible expiry is the failure this whole feature is built to avoid.
 *
 * Revoking takes a confirmation, but only one: unlike unpairing it destroys nothing and can be
 * undone by inviting again, which the dialog says.
 *
 * @param onNavigateUp Returns to Settings.
 * @param onOpenMyProfile Opens the friend's own profile — only reachable when signed in as one.
 * @param viewModel Screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onNavigateUp: () -> Unit,
    onOpenMyProfile: () -> Unit,
    viewModel: FriendViewModel = hiltViewModel()
) {
    val friends by viewModel.friends.collectAsState()
    val invite by viewModel.invite.collectAsState()
    val myGrant by viewModel.myGrant.collectAsState()
    val redeem by viewModel.redeem.collectAsState()

    // Saveable so a rotation mid-confirmation does not silently drop the decision.
    var pendingRevoke by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.friend_section_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The friend's own view of this screen: their grant, and the way into their
            // profile. A friend has no family friends to list and no invitations to mint, so
            // they see neither — the screen answers whichever side of the relationship is
            // signed in rather than showing controls that would be refused.
            myGrant?.let { grant ->
                val until = remember(grant.expiresAtMillis) {
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(
                        Instant.ofEpochMilli(grant.expiresAtMillis)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                    )
                }
                SectionGroup {
                    SectionRow(
                        icon = Icons.Default.Diversity3,
                        title = stringResource(R.string.friend_profile_title),
                        supporting = stringResource(R.string.friend_access_until, until),
                        onClick = onOpenMyProfile,
                        trailing = {}
                    )
                }
                return@Column
            }

            if (friends.isEmpty()) {
                Text(
                    text = stringResource(R.string.friend_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Somebody handed a code needs somewhere to type it, and this screen is where
                // Settings sends them. Only while they hold no grant — the branch above
                // returns once they do.
                FriendRedeemRow(
                    state = redeem,
                    onCodeChange = viewModel::updateCode,
                    onRedeem = viewModel::redeemCode
                )
            } else {
                SectionGroup {
                    friends.forEachIndexed { index, grant ->
                        FriendRow(grant = grant, onRevoke = { pendingRevoke = grant.friendUid })
                        if (index != friends.lastIndex) Divider()
                    }
                }
            }

            SectionGroup {
                SectionRow(
                    icon = Icons.Default.PersonAdd,
                    title = stringResource(R.string.friend_invite_action),
                    onClick = viewModel::openInvite
                )
            }
        }
    }

    if (invite.isOpen) {
        FriendInviteSheet(
            state = invite,
            onChooseDuration = viewModel::chooseDuration,
            onCreate = viewModel::createInvite,
            onDismiss = viewModel::dismissInvite
        )
    }

    pendingRevoke?.let { uid ->
        val name = friends.firstOrNull { it.friendUid == uid }?.name.orEmpty()
        ConfirmationDialog(
            title = stringResource(R.string.friend_revoke_confirm_title, name),
            message = stringResource(R.string.friend_revoke_confirm_message),
            confirmText = stringResource(R.string.friend_revoke),
            dismissText = stringResource(R.string.pairing_cancel),
            onConfirm = {
                viewModel.revoke(uid)
                pendingRevoke = null
            },
            onDismiss = { pendingRevoke = null },
            isDestructive = true
        )
    }
}

/** One friend: their name in the friend colour, when their access ends, and how to end it. */
@Composable
private fun FriendRow(grant: CalendarFriendGrant, onRevoke: () -> Unit) {
    val until = remember(grant.expiresAtMillis) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(
                Instant.ofEpochMilli(grant.expiresAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            )
    }
    SectionRow(
        icon = Icons.Default.Diversity3,
        title = grant.name,
        supporting = stringResource(R.string.friend_access_until, until),
        // The one control on the row, per the design language's "at most one trailing control".
        onClick = onRevoke,
        trailing = {
            Text(
                text = stringResource(R.string.friend_revoke),
                style = MaterialTheme.typography.labelMedium,
                color = CoPlanlyColors.FriendTeal
            )
        }
    )
}

/**
 * "I have a code": one field and one button, for the friend rather than the parent.
 *
 * Reaches the friend callable only. A co-parent or guest code typed here is refused and *said*
 * so — the two other kinds exist, somebody may well have been sent both, and the remedy differs.
 */
@Composable
private fun FriendRedeemRow(
    state: FriendRedeemState,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.friend_invite_code_label)) },
            singleLine = true,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth()
        )
        state.errorRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = onRedeem,
            enabled = !state.isBusy && state.code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.pairing_link_accounts))
        }
    }
}
