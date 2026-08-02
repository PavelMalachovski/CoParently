package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite

/** One invitation addressed to this user, with accept and decline. */
@Composable
fun IncomingInviteCard(
    invite: PairingInvite,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = invite.fromUserName.ifEmpty {
                        stringResource(R.string.pairing_unknown_sender)
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = invite.fromUserEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                TextButton(onClick = onAccept) {
                    Text(stringResource(R.string.pairing_accept_button))
                }
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.pairing_reject_button))
                }
            }
        }
    }
}
