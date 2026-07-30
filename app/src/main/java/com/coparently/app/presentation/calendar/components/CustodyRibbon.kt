package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.custody.HandoverInfo
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.utils.LightDarkPreviews
import com.coparently.app.utils.PreviewWrapper
import java.time.LocalDate

/** Ribbon height. Replaces a 48dp outlined card that carried the same single fact. */
private val RIBBON_HEIGHT = 34.dp

/** Full-hue bar on the start edge, so the ribbon reads as custody even at a glance. */
private val RIBBON_EDGE_WIDTH = 3.dp

/**
 * Compact "who has the child today" ribbon for the top of the calendar.
 *
 * Replaces the old 48dp outlined banner, which spent a lot of height saying only "With Dad".
 * This one is 34dp and additionally answers *when it changes* — the question a co-parent
 * actually has — using the shared handover calculation.
 *
 * Renders nothing when [custody] is neither "mom" nor "dad": with no custody model configured
 * there is no honest answer to show.
 *
 * @param custody Custody owner today: "mom" or "dad"
 * @param handover Next handover, or null when unknown or custody never switches
 * @param modifier Modifier for the ribbon container
 */
@Composable
fun CustodyRibbon(
    custody: String,
    handover: HandoverInfo?,
    modifier: Modifier = Modifier
) {
    val accent = when (custody) {
        "mom" -> CoPlanlyColors.MomPink
        "dad" -> CoPlanlyColors.DadBlue
        else -> return
    }

    val todayText = stringResource(R.string.calendar_custody_today_with, parentName(custody))

    // Only offered when the handover is for the *other* parent; a model that never switches
    // leaves this null and the ribbon simply says who has today.
    val handoverText = handover?.let {
        if (it.daysUntil <= 1L) {
            stringResource(R.string.calendar_custody_handover_tomorrow, parentName(it.toParent))
        } else {
            pluralStringResource(
                R.plurals.calendar_custody_handover_in_days,
                it.daysUntil.toInt(),
                it.daysUntil.toInt(),
                parentName(it.toParent)
            )
        }
    }

    val description = if (handover == null) {
        stringResource(R.string.calendar_custody_ribbon_description, parentName(custody))
    } else {
        stringResource(
            R.string.calendar_custody_ribbon_description_handover,
            parentName(custody),
            parentName(handover.toParent),
            handover.daysUntil.toInt()
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RIBBON_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA))
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(RIBBON_EDGE_WIDTH)
                .background(accent)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = todayText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (handoverText != null) {
                Text(
                    text = handoverText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/** Localised display name for a `"mom"`/`"dad"` custody value. */
@Composable
private fun parentName(parent: String): String = when (parent) {
    "mom" -> stringResource(R.string.calendar_parent_mom)
    else -> stringResource(R.string.calendar_parent_dad)
}

// ==================== Previews ====================

@LightDarkPreviews
@Composable
private fun CustodyRibbonMomPreview() {
    PreviewWrapper {
        CustodyRibbon(
            custody = "mom",
            handover = HandoverInfo(
                date = LocalDate.now().plusDays(2),
                daysUntil = 2,
                fromParent = "mom",
                toParent = "dad"
            )
        )
    }
}

@Preview(name = "Dad, handover tomorrow", showBackground = true)
@Composable
private fun CustodyRibbonDadTomorrowPreview() {
    PreviewWrapper {
        CustodyRibbon(
            custody = "dad",
            handover = HandoverInfo(
                date = LocalDate.now().plusDays(1),
                daysUntil = 1,
                fromParent = "dad",
                toParent = "mom"
            )
        )
    }
}

@Preview(name = "No handover known", showBackground = true)
@Composable
private fun CustodyRibbonNoHandoverPreview() {
    PreviewWrapper {
        CustodyRibbon(custody = "mom", handover = null)
    }
}
