package com.coparently.app.presentation.pairing.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.presentation.common.dashedRoundedBorder
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Side of the inline QR code. */
private val QR_SIZE = 180.dp

/**
 * The hero card of the "share my code" mode: the invite code, how long it lasts, a scannable
 * QR, and the two ways to hand it over.
 *
 * Reworked by the August 2026 design review, which found the code was a bare `TextButton` whose
 * tap copied it — with nothing on screen saying so — and that the QR sat behind a dialog even
 * though showing it is most of what this screen is for. The code now sits in a dashed container
 * with a copy glyph and an explicit "tap to copy" line, and the QR renders inline.
 *
 * Always fills the width of its container, so unlike the other pairing components it takes no
 * `modifier` — adding one would push this composable past detekt's parameter-count limit for
 * no actual caller need.
 *
 * @param invite The outstanding invite
 * @param qrBitmap Rendered QR for the invite link, or null while it is still being generated
 * @param onCopy Copies the code to the clipboard
 * @param onShare Opens the system share sheet with the invite message
 * @param onRegenerate Issues a fresh code
 */
@Composable
// One card, one parameter per thing it shows or does; code, QR and actions are one visual unit.
@Suppress("LongParameterList", "LongMethod")
fun InviteCodeCard(
    invite: PairingInvite,
    qrBitmap: Bitmap?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.pairing_your_code_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Dashed container + copy glyph: the affordance the old bare code had none of.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .dashedRoundedBorder(
                        color = MaterialTheme.colorScheme.outline,
                        cornerRadius = 16.dp
                    )
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = invite.code,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 7.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.pairing_copy_code),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = stringResource(
                    R.string.pairing_tap_to_copy_and_expiry,
                    countdownText(invite.expiresAtMillis)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Inline, not behind a dialog — handing the code over is most of what this
            // screen is for, and a QR the other parent cannot see is not handing anything over.
            Box(
                modifier = Modifier
                    .size(QR_SIZE)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.inverseOnSurface),
                contentAlignment = Alignment.Center
            ) {
                qrBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription =
                        stringResource(R.string.pairing_qr_code_content_description),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.pairing_share_link),
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
