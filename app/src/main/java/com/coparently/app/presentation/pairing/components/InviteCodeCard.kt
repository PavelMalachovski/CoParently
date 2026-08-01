package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * The hero card of the unpaired state: the invite code, how long it lasts, and
 * the three ways to hand it over.
 *
 * Always fills the width of its container, so unlike the other pairing
 * components it takes no `modifier` — adding one would push this composable
 * past detekt's parameter-count limit for no actual caller need.
 */
@Composable
fun InviteCodeCard(
    invite: PairingInvite,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
    onRegenerate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.pairing_your_code_title),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onCopy) {
                Text(
                    text = invite.code,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    style = MaterialTheme.typography.displaySmall
                )
            }
            Text(
                text = countdownText(invite.expiresAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.pairing_your_code_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text(
                        text = stringResource(R.string.pairing_share_invite),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                OutlinedButton(onClick = onShowQr) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Text(
                        text = stringResource(R.string.pairing_show_qr),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            TextButton(onClick = onRegenerate) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(
                    text = stringResource(R.string.pairing_new_code),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * Live "valid for …" text, refreshed periodically until the code expires —
 * the refresh loop then stops on its own instead of waking up forever to
 * recompute an already-frozen "expired" string.
 */
@Composable
private fun countdownText(expiresAtMillis: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (now < expiresAtMillis) {
            delay(TimeUnit.SECONDS.toMillis(REFRESH_INTERVAL_SECONDS))
            now = System.currentTimeMillis()
        }
    }
    val remaining = (expiresAtMillis - now).coerceAtLeast(0)
    if (remaining == 0L) return stringResource(R.string.pairing_code_expired_generate)
    val hours = TimeUnit.MILLISECONDS.toHours(remaining)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % MINUTES_PER_HOUR
    return if (hours > 0) {
        stringResource(R.string.pairing_expires_in_hours, hours, minutes)
    } else {
        stringResource(R.string.pairing_expires_in_minutes, minutes)
    }
}

/** How often [countdownText] refreshes while the card is on screen. */
private const val REFRESH_INTERVAL_SECONDS = 30L

private const val MINUTES_PER_HOUR = 60L
