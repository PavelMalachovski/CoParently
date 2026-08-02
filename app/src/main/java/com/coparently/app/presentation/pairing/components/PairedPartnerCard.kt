package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.PartnerSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Summary of the linked co-parent.
 *
 * [PartnerSummary.name] and [PartnerSummary.email] can both be blank — the
 * repository falls back to an empty [PartnerSummary] when the partner's
 * profile document fails to load, rather than dropping the pairing itself.
 * This card must not render that as if it were real data: a blank name shows
 * a translated placeholder (and feeds the avatar initial instead of an empty
 * circle), and a blank email shows its own placeholder.
 */
@Composable
fun PairedPartnerCard(
    partner: PartnerSummary,
    modifier: Modifier = Modifier
) {
    val displayName = partner.name.ifBlank { stringResource(R.string.pairing_unknown_sender) }
    val displayEmail = partner.email.ifBlank { stringResource(R.string.pairing_partner_email_unknown) }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = displayEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                partner.pairedSinceMillis?.let { millis ->
                    Text(
                        text = stringResource(R.string.pairing_paired_since, formatDate(millis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Formats an epoch-millis instant as a localized date.
 *
 * `LocalDate.ofInstant` is API 34+, and minSdk here is 26 — go through the zone
 * explicitly instead.
 */
private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
