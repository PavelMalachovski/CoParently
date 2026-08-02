package com.coparently.app.presentation.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a dashed rounded-rectangle border around the content.
 *
 * Compose's `Modifier.border` only strokes solid lines, so the pairing screen's tap-to-copy
 * code container — a dashed outline that reads as "this is a thing you take, not a heading" —
 * has to draw its own.
 *
 * @param color Stroke colour
 * @param cornerRadius Corner radius, matched to the container's own clip
 * @param strokeWidth Line thickness
 * @param dashLength Length of each dash
 * @param gapLength Gap between dashes
 */
fun Modifier.dashedRoundedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.dp,
    dashLength: Dp = 6.dp,
    gapLength: Dp = 4.dp
): Modifier = drawBehind {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(dashLength.toPx(), gapLength.toPx()),
            phase = 0f
        )
    )
    val inset = stroke.width / 2f
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = Size(size.width - stroke.width, size.height - stroke.width),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = stroke
    )
}
