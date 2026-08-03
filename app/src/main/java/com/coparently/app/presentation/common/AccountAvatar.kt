package com.coparently.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * A person's avatar: their photo if there is one, otherwise the first letter of their name
 * in a filled circle.
 *
 * Used for both parents — the signed-in account and the paired co-parent — so the two read
 * as one treatment. That matters here because the two sides get their photo at different
 * times: the co-parent's only appears once *their* phone has run a build that stores one,
 * so one side showing a photo while the other shows a letter is a normal, indefinite state
 * rather than a glitch.
 *
 * Deliberately neutral: the circle uses the theme's `secondaryContainer` (a neutral indigo
 * in this app), never Mom-pink or Dad-blue — those are parent *identity* colours and would
 * assert a role this composable knows nothing about.
 *
 * The initial is not a placeholder in the loading sense; it is the answer whenever the
 * photo is absent, still loading, or failed to load, so a slow network or a dead URL
 * degrades to something meaningful instead of an empty circle.
 *
 * Decorative by design — the name it stands for is always rendered as text next to it, so
 * it carries no content description of its own.
 *
 * @param name Display name the initial is taken from; may be blank, in which case the
 *   circle simply renders empty
 * @param photoUrl Remote avatar to load, or null/blank for the initial
 * @param modifier Modifier for the avatar circle
 * @param size Diameter of the circle
 */
@Composable
fun AccountAvatar(
    name: String,
    photoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_AVATAR_SIZE
) {
    val initial = name.trim().take(1).uppercase()
    val initialStyle = if (size < COMPACT_AVATAR_SIZE) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.titleLarge
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val initialFallback: @Composable () -> Unit = {
            Text(
                text = initial,
                style = initialStyle,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        if (photoUrl.isNullOrBlank()) {
            initialFallback()
        } else {
            SubcomposeAsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { initialFallback() },
                error = { initialFallback() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Matches the co-parent card, where the avatar is the card's leading element. */
private val DEFAULT_AVATAR_SIZE = 48.dp

/** Below this, the initial needs the smaller of the two title styles to fit the circle. */
private val COMPACT_AVATAR_SIZE = 40.dp
