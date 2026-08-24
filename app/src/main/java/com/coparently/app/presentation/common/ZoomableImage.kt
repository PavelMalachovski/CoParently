package com.coparently.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.coparently.app.R

/** Furthest in a pinch may go. Beyond this a 1600px upload is only bigger, not clearer. */
private const val MAX_SCALE = 5f

/** Where a double tap lands, and what a second double tap comes back from. */
private const val DOUBLE_TAP_SCALE = 2.5f

/** Below this the view counts as "not zoomed": panning is off and a tap dismisses. */
private const val UNZOOMED_EPSILON = 1.01f

/**
 * An image that pinches, pans and double-taps to zoom.
 *
 * Two things here are load-bearing rather than stylistic.
 *
 * **It requests [Size.ORIGINAL] from Coil.** Coil sizes a decode from the measured constraint,
 * so an image loaded at container size and then scaled up by `graphicsLayer` is a blurred
 * upscale of a phone-width bitmap — the pinch would work and reveal nothing, which is the same
 * complaint it is meant to answer. The uploads this shows are already capped at
 * `FirebaseImageStorage.MAX_DIMENSION_PX`, so "original" is bounded.
 *
 * **A tap only dismisses while unzoomed.** The receipt viewer this replaces put
 * `clickable(onClick = onDismiss)` on the image itself, so any exploratory tap while reading a
 * receipt closed it. Once zoomed, [onTap] is not called at all: the way out is the close control
 * or a double tap back to fit.
 *
 * @param model What Coil should load — a download URL, a content URI, anything `AsyncImage`
 *   accepts.
 * @param contentDescription Read by a screen reader; null when a caller has already described
 *   this image on the way in.
 * @param modifier Modifier applied to the gesture surface.
 * @param onTap Invoked on a single tap **while unzoomed**, or null for an inert image.
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    var scale by remember(model) { mutableFloatStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(model) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    // Panning below 1f would drag an image that does not overflow its box.
                    offset = if (next <= 1f) {
                        Offset.Zero
                    } else {
                        clampPan(offset + pan, next, containerSize)
                    }
                    scale = next
                }
            }
            .pointerInput(model) {
                detectTapGestures(
                    onTap = { if (scale <= UNZOOMED_EPSILON) onTap?.invoke() },
                    onDoubleTap = { position ->
                        if (scale > UNZOOMED_EPSILON) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                            // Zoom towards the tapped point rather than the centre: a receipt's
                            // total is in a corner, and a centre-anchored zoom pushes it off.
                            offset = clampPan(
                                focusOffset(position, DOUBLE_TAP_SCALE, containerSize),
                                DOUBLE_TAP_SCALE,
                                containerSize
                            )
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(model)
                .size(Size.ORIGINAL)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

/**
 * Keeps a pan inside the overflow the current zoom actually creates.
 *
 * At scale `s` the content overflows its box by `(s - 1) * size` in each axis, half of it on
 * each side, so that half is exactly how far the content may travel before its edge would show.
 * Clamping against the container rather than the drawn image is deliberate: with
 * [ContentScale.Fit] the drawn bounds depend on the bitmap's aspect ratio, which is not known
 * here, and the looser bound only ever allows a little letterbox to be dragged into view.
 */
private fun clampPan(pan: Offset, scale: Float, container: IntSize): Offset {
    if (container == IntSize.Zero) return Offset.Zero
    val maxX = (scale - 1f) * container.width / 2f
    val maxY = (scale - 1f) * container.height / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/**
 * The translation that brings [position] to the centre when zooming to [scale] from fit.
 *
 * Measured from the box's centre, a point `d` away from it sits `d * scale` away once scaled, so
 * translating by `-d * (scale - 1)` puts it back under the finger.
 */
private fun focusOffset(position: Offset, scale: Float, container: IntSize): Offset {
    if (container == IntSize.Zero) return Offset.Zero
    val dx = position.x - container.width / 2f
    val dy = position.y - container.height / 2f
    return Offset(-dx * (scale - 1f), -dy * (scale - 1f))
}

/**
 * One image, filling the screen, zoomable.
 *
 * `usePlatformDefaultWidth = false` so the content is readable: the default dialog width is
 * sized for text and would letterbox the thing the user opened it to see.
 *
 * @param model What Coil should load.
 * @param contentDescription Read by a screen reader, or null when the caller already described
 *   the image on the thumbnail the user tapped.
 * @param onDismiss Closes the viewer — from the close control, the system back gesture, or a
 *   single tap while the image is not zoomed.
 */
@Composable
fun FullScreenImageDialog(
    model: Any?,
    contentDescription: String?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            ZoomableImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                onTap = onDismiss
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.image_viewer_close),
                    tint = Color.White
                )
            }
        }
    }
}
