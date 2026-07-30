package com.coparently.app.presentation.calendar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.utils.LightDarkPreviews
import com.coparently.app.utils.PreviewWrapper

/**
 * Key to the month grid's colour language: Mom pink, Dad blue, and the teal school-vacation
 * strip.
 *
 * The grid has always encoded three separate things in colour and never said so anywhere. This
 * fills the dead space beside the FAB, which was the only place in the layout with room to
 * spare.
 */
@Composable
fun CalendarLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics {
            contentDescription =
                "Legend: pink is Mom, blue is Dad, a teal strip marks school vacation"
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(
            label = stringResource(R.string.calendar_legend_mom),
            swatch = { Swatch(CoPlanlyColors.MomPink) }
        )
        LegendItem(
            label = stringResource(R.string.calendar_legend_dad),
            swatch = { Swatch(CoPlanlyColors.DadBlue) }
        )
        LegendItem(
            label = stringResource(R.string.calendar_legend_vacation),
            swatch = {
                Box(
                    modifier = Modifier
                        .size(width = 10.dp, height = 3.dp)
                        .background(CoPlanlyColors.VacationTint, RoundedCornerShape(2.dp))
                )
            }
        )
    }
}

/** One swatch-plus-label pair. */
@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        swatch()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Small rounded square used for the two parent colours. */
@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(color, RoundedCornerShape(2.dp))
    )
}

@LightDarkPreviews
@Composable
private fun CalendarLegendPreview() {
    PreviewWrapper {
        CalendarLegend()
    }
}
