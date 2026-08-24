package com.coparently.app.presentation.expenses

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.coparently.app.domain.expenses.CategorySlice
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.presentation.theme.CoPlanlyTheme
import com.coparently.app.utils.LightDarkPreviews
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** A full turn, in degrees. */
private const val FULL_TURN = 360f

/** Twelve o'clock, in the coordinate system `drawArc` uses (0° is three o'clock). */
private const val TWELVE_OCLOCK = -90f

/** The separator between touching arcs, in surface colour. Two pixels, per the mark spec. */
private val ARC_GAP = 2.dp

/** How much of the chart's box the pie itself takes, leaving room for the gap ring to breathe. */
private const val PIE_INSET_FRACTION = 0.04f

/**
 * The narrowest slice that carries an in-slice share label, in degrees. Below this the text
 * outgrows its wedge; the table beneath the chart still names those figures.
 */
private const val MIN_LABEL_SWEEP_DEGREES = 28f

/** Where the label sits along the slice's mid-angle, as a fraction of the radius. */
private const val LABEL_RADIUS_FRACTION = 0.62f

/** Degrees to radians. */
private const val DEGREES_TO_RADIANS = Math.PI / 180.0

/** Above this luminance the label reads better dark-on-light than light-on-dark. */
private const val LABEL_LUMINANCE_FLIP = 0.5f

/**
 * A month's spending as a pie: one arc per category, in fixed category order, clockwise from
 * twelve o'clock.
 *
 * **Drawn, not imported.** A pie is `drawArc` in a loop; a charting dependency for one screen is
 * a large surface for a small need, and this project has none. The one thing a library would add
 * — animation and touch hit-testing — is not asked for here.
 *
 * **The chart is not the accessible representation; the table beneath it is.** So it carries no
 * content description at all: a screen reader announcing nine slice angles would be a worse
 * version of the table sitting directly below, which has the same figures as text. The semantics
 * are cleared outright rather than merely set to null, so the `Canvas` cannot leak anything.
 *
 * **Arcs are drawn in category order, not by amount.** Only neighbouring arcs touch, and
 * [CategoryPalette] spaces neighbouring *categories* far apart on the colour wheel — sorting by
 * amount would put an arbitrary pair of colours next to each other on every filter change, which
 * is precisely the pairing the palette cannot guarantee. The table below is sorted by amount,
 * which is where item 14's "largest first" belongs: it is a reading order, and a pie has none.
 *
 * @param slices The categories with something in them. Order is irrelevant — this sorts by
 *   category itself — but every slice's [CategorySlice.share] must be its share of one currency's
 *   total, never of a mixed one.
 * @param modifier Modifier applied to the chart.
 */
@Composable
fun CategoryPieChart(
    slices: List<CategorySlice>,
    modifier: Modifier = Modifier
) {
    // Resolved outside the draw scope: `sliceColor()` is a composable and `Canvas` is not a
    // composable context.
    val ordered = slices.sortedBy { it.category.ordinal }
    val colors = ordered.map { it.category.sliceColor() }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(ARC_GAP)) {
            drawPie(ordered, colors, textMeasurer, labelStyle)
        }
    }
}

/**
 * Draws the arcs.
 *
 * Three guards, each one line, none visible in a screenshot of the happy path:
 *
 * - **No slices draws an empty canvas**, not a crash. The aggregation never emits a zero-total
 *   currency, so this arrives only from a caller with nothing to show — the analytics view's
 *   empty state, which renders its own message instead.
 * - **A single slice is a full circle**, not a 360°-minus-a-gap arc with a seam in it. There is
 *   nothing for it to touch, so there is nothing for a gap to separate.
 * - **A slice narrower than the gap draws nothing rather than a negative sweep.** `drawArc` with
 *   a negative sweep paints backwards over its neighbour, which is a wrong chart rather than a
 *   missing one.
 */
private fun DrawScope.drawPie(
    slices: List<CategorySlice>,
    colors: List<Color>,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    if (slices.isEmpty()) return

    val inset = size.minDimension * PIE_INSET_FRACTION
    val diameter = size.minDimension - inset * 2
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val arcSize = Size(diameter, diameter)

    if (slices.size == 1) {
        drawArc(colors.first(), TWELVE_OCLOCK, FULL_TURN, true, topLeft, arcSize)
        val midAngle = TWELVE_OCLOCK + FULL_TURN / 2f
        drawSliceLabel(slices.first(), colors.first(), midAngle, diameter, textMeasurer, labelStyle)
        return
    }

    // The gap is taken out of each arc rather than drawn over it: a stroke around a mark adds
    // ink that is not data, and the separation is meant to come from the surface showing
    // through. Converting it to degrees at this radius keeps it a constant 2dp on screen
    // whatever size the chart is laid out at.
    val gapDegrees = (ARC_GAP.toPx() / (Math.PI.toFloat() * diameter)) * FULL_TURN

    var start = TWELVE_OCLOCK
    slices.forEachIndexed { index, slice ->
        val sweep = (slice.share * FULL_TURN).toFloat()
        // A slice smaller than the gap itself would render as a sliver of surface, or invert.
        // Draw it at whatever is left rather than negative.
        val visible = (sweep - gapDegrees).coerceAtLeast(0f)
        if (visible > 0f) {
            drawArc(colors[index], start + gapDegrees / 2f, visible, true, topLeft, arcSize)
        }
        // A share label on every slice wide enough to hold one. Narrower slices stay silent —
        // the table beneath the chart carries their figures — and the guard is on the *sweep*,
        // so which slices speak does not change with the chart's laid-out size.
        if (sweep >= MIN_LABEL_SWEEP_DEGREES) {
            drawSliceLabel(slice, colors[index], start + sweep / 2f, diameter, textMeasurer, labelStyle)
        }
        start += sweep
    }
}

/**
 * The slice's share ("34%"), centred along its mid-angle, in whichever of near-black or white
 * reads against this slice's own fill — the palette's hues span both sides of that line.
 */
@Suppress("LongParameterList") // one label's geometry, expressed as one parameter list
private fun DrawScope.drawSliceLabel(
    slice: CategorySlice,
    sliceColor: Color,
    midAngleDegrees: Float,
    diameter: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    val format = NumberFormat.getPercentInstance(Locale.getDefault())
    format.maximumFractionDigits = 0
    val text = AnnotatedString(format.format(slice.share))
    val contrast = if (sliceColor.luminance() > LABEL_LUMINANCE_FLIP) {
        Color.Black.copy(alpha = 0.87f)
    } else {
        Color.White
    }
    val layout = textMeasurer.measure(text, labelStyle)
    val radius = diameter / 2f * LABEL_RADIUS_FRACTION
    val angleRadians = midAngleDegrees * DEGREES_TO_RADIANS
    val centre = Offset(size.width / 2f, size.height / 2f)
    val labelCentre = Offset(
        centre.x + radius * cos(angleRadians).toFloat(),
        centre.y + radius * sin(angleRadians).toFloat()
    )
    drawText(
        textLayoutResult = layout,
        color = contrast,
        topLeft = Offset(
            labelCentre.x - layout.size.width / 2f,
            labelCentre.y - layout.size.height / 2f
        )
    )
}

@LightDarkPreviews
@Composable
private fun CategoryPieChartOneSlicePreview() {
    CoPlanlyTheme {
        CategoryPieChart(
            slices = listOf(CategorySlice(ExpenseCategory.EDUCATION, 1200.0, 1.0)),
            modifier = Modifier.size(200.dp)
        )
    }
}

@LightDarkPreviews
@Composable
private fun CategoryPieChartAllNinePreview() {
    // The worst case the palette has to survive, and the one a device check cannot easily
    // produce: every category present at once.
    val share = 1.0 / ExpenseCategory.entries.size
    CoPlanlyTheme {
        CategoryPieChart(
            slices = ExpenseCategory.entries.map { CategorySlice(it, 100.0, share) },
            modifier = Modifier.size(200.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CategoryPieChartEmptyPreview() {
    CoPlanlyTheme {
        CategoryPieChart(slices = emptyList(), modifier = Modifier.size(200.dp))
    }
}
