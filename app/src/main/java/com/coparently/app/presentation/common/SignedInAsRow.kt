package com.coparently.app.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.AccountSummary

/**
 * "Signed in as <name> / <email>", with the account's avatar.
 *
 * A quiet identity strip rather than a card: on the pairing screen it sits above the
 * invite-code hero card and must not compete with it, and in Settings it sits inside the
 * account card, which already provides the surface. Both parents' phones show the same
 * invite-code layout, which is exactly why the account has to be named somewhere obvious —
 * without it there is nothing on the screen that tells the two devices apart.
 *
 * @param account The signed-in account; callers render nothing when there is none
 * @param modifier Modifier for the row
 */
@Composable
fun SignedInAsRow(
    account: AccountSummary,
    modifier: Modifier = Modifier
) {
    // AccountSummary already falls back to the email local part, so a blank name means the
    // account has nothing but an address — show the address itself rather than an empty line.
    val displayName = account.name.ifBlank { account.email }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountAvatar(name = displayName, photoUrl = account.photoUrl, size = 36.dp)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = stringResource(R.string.common_signed_in_as),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (account.email.isNotBlank() && account.email != displayName) {
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
