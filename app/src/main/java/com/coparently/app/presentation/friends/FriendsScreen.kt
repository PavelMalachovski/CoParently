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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.friends.CalendarFriendGrant
import com.coparently.app.presentation.common.AccountAvatar
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
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
 * A row opens that friend's card rather than removing them: their phone number and blood group
 * are the reason the profile exists, and a list that only ever offered "remove access" would put
 * a destructive action where a person's name is and hide everything worth reading.
 *
 * @param onNavigateUp Returns to Settings.
 * @param onOpenFriend Opens one friend's card, by uid.
 * @param onOpenMyProfile Opens the friend's own profile — only reachable when signed in as one.
 * @param viewModel Screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onNavigateUp: () -> Unit,
    onOpenFriend: (String) -> Unit,
    onOpenMyProfile: () -> Unit,
    viewModel: FriendViewModel = hiltViewModel()
) {
    val friends by viewModel.friends.collectAsState()
    val invite by viewModel.invite.collectAsState()
    val myGrant by viewModel.myGrant.collectAsState()
    val redeem by viewModel.redeem.collectAsState()

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
                        FriendRow(grant = grant, onOpen = { onOpenFriend(grant.friendUid) })
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
}

/** One friend: their face, their name, when their access ends, and the way into their card. */
@Composable
private fun FriendRow(grant: CalendarFriendGrant, onOpen: () -> Unit) {
    val until = remember(grant.expiresAtMillis) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(
                Instant.ofEpochMilli(grant.expiresAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            )
    }
    SectionRow(
        title = grant.name,
        supporting = stringResource(R.string.friend_access_until, until),
        // Their Google account's own picture, copied into the grant at accept time so this list
        // needs no second read; `AccountAvatar` falls back to the initial when there is none.
        leading = { AccountAvatar(name = grant.name, photoUrl = grant.photoUrl, size = 32.dp) },
        // The one control on the row, per the design language's "at most one trailing control".
        onClick = onOpen,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
