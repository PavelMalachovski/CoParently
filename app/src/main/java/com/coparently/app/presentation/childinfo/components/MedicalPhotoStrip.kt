package com.coparently.app.presentation.childinfo.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.coparently.app.R
import com.coparently.app.presentation.common.PhotoStrip
import com.coparently.app.presentation.common.PhotoStripStrings

/**
 * The photographs attached to a child's medical notes.
 *
 * A thin binding of the shared [PhotoStrip] to the medical strings — the strip's behaviour
 * (read-only by default, removal is a request settled on save, full-screen open) lives in
 * `presentation/common/PhotoStrip.kt`, where the pet record shares it.
 *
 * @param photos What to show: download URLs already on the record, and content URIs picked
 *   on this device and not yet uploaded.
 * @param modifier Modifier applied to the strip.
 * @param onAdd Opens the picker, or null for a read-only strip.
 * @param onRemove Asks for a photograph to be removed, or null for a read-only strip.
 * @param enabled Whether the controls accept input; ignored when read-only.
 */
@Composable
fun MedicalPhotoStrip(
    photos: List<String>,
    modifier: Modifier = Modifier,
    onAdd: (() -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null,
    enabled: Boolean = true
) {
    PhotoStrip(
        photos = photos,
        strings = PhotoStripStrings(
            title = R.string.medical_photos_title,
            empty = R.string.medical_photos_none,
            add = R.string.medical_photos_add,
            hint = R.string.medical_photos_hint,
            thumbnailDescription = R.string.medical_photos_thumbnail_description,
            open = R.string.medical_photos_open,
            remove = R.string.medical_photos_remove,
            close = R.string.medical_photos_close
        ),
        modifier = modifier,
        onAdd = onAdd,
        onRemove = onRemove,
        enabled = enabled
    )
}
