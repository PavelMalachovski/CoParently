package com.coparently.app.presentation.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/** Edge of a square thumbnail in the strip. Large enough to recognise what is in it. */
private val THUMBNAIL_SIZE = 96.dp

/**
 * The string resources one [PhotoStrip] renders with.
 *
 * A value type rather than eight parameters, so a new caller cannot accidentally mix the
 * medical strings with the pet ones. [thumbnailDescription] takes two format arguments —
 * position and total — because the description counts rather than describes: nothing in the
 * app knows what is in the picture.
 */
data class PhotoStripStrings(
    @StringRes val title: Int,
    @StringRes val empty: Int,
    @StringRes val add: Int,
    @StringRes val hint: Int,
    @StringRes val thumbnailDescription: Int,
    @StringRes val open: Int,
    @StringRes val remove: Int,
    @StringRes val close: Int
)

/**
 * Photographs attached to a record: a scrolling strip of thumbnails, each tapping open
 * full-screen.
 *
 * Extracted from the child record's medical photo strip so the pet record does not have to
 * copy it — behaviour is identical, only the strings differ. Read-only by default; pass
 * [onAdd] and [onRemove] to make it an editor, so the summary view and the edit form never
 * drift into different ideas of what a photograph looks like.
 *
 * **Removal here is a request, not a deletion.** The caller collects it and, on save,
 * deletes the object *before* dropping the URL from the record. A strip that deleted on tap
 * would have to either block on the network or lie about having done it.
 *
 * @param photos What to show: download URLs already on the record, and content URIs picked
 *   on this device and not yet uploaded. Coil loads both.
 * @param strings The string resources to render with.
 * @param modifier Modifier applied to the strip.
 * @param onAdd Opens the picker, or null for a read-only strip.
 * @param onRemove Asks for a photograph to be removed, or null for a read-only strip.
 * @param enabled Whether the controls accept input; ignored when read-only.
 */
@Composable
fun PhotoStrip(
    photos: List<String>,
    strings: PhotoStripStrings,
    modifier: Modifier = Modifier,
    onAdd: (() -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null,
    enabled: Boolean = true
) {
    var opened by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(strings.title),
            style = MaterialTheme.typography.titleSmall
        )

        if (photos.isEmpty()) {
            Text(
                text = stringResource(strings.empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                photos.forEachIndexed { index, photo ->
                    Thumbnail(
                        photo = photo,
                        position = index + 1,
                        total = photos.size,
                        strings = strings,
                        onOpen = { opened = photo },
                        onRemove = onRemove?.takeIf { enabled }
                    )
                }
            }
        }

        if (onAdd != null) {
            OutlinedButton(
                onClick = onAdd,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = null)
                Text(
                    text = stringResource(strings.add),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                text = stringResource(strings.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    opened?.let { photo ->
        FullScreenPhoto(photo = photo, strings = strings, onClose = { opened = null })
    }
}

/** One thumbnail, with its remove button when the strip is an editor. */
@Composable
private fun Thumbnail(
    photo: String,
    position: Int,
    total: Int,
    strings: PhotoStripStrings,
    onOpen: () -> Unit,
    onRemove: ((String) -> Unit)?
) {
    Box(modifier = Modifier.size(THUMBNAIL_SIZE)) {
        AsyncImage(
            model = photo,
            contentDescription = stringResource(strings.thumbnailDescription, position, total),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.medium)
                .clickable(
                    onClick = onOpen,
                    onClickLabel = stringResource(strings.open)
                )
        )
        if (onRemove != null) {
            FilledTonalIconButton(
                onClick = { onRemove(photo) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(strings.remove),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * One photograph, filling the screen.
 *
 * `usePlatformDefaultWidth = false` so the content is readable: the default dialog width is
 * sized for text and would letterbox the thing the user opened it to see.
 */
@Composable
private fun FullScreenPhoto(
    photo: String,
    strings: PhotoStripStrings,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Zoomable: a prescription or a vaccination card is read, not glanced at, and the
            // fit-to-screen render this replaced made small print unreadable on a phone.
            ZoomableImage(
                model = photo,
                // The strip's thumbnail already described this one; repeating it here would
                // have a screen reader announce the same photograph twice on the way in.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                onTap = onClose
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(strings.close),
                    tint = Color.White
                )
            }
        }
    }
}
