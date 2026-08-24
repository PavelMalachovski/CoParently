package com.coparently.app.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coparently.app.presentation.theme.dimensions

/**
 * Builds the animated gradient behind the shimmer effect.
 *
 * @return A brush carrying an animated horizontal gradient
 */
@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnimation, translateAnimation),
        end = Offset(translateAnimation + 200f, translateAnimation + 200f)
    )
}

/**
 * Placeholder rows for a list of events.
 * Draws several animated event placeholders.
 *
 * @param modifier Modifier for customisation
 * @param count How many placeholder rows to draw
 */
@Composable
fun EventListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 3
) {
    val dims = dimensions()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(dims.paddingMedium),
        verticalArrangement = Arrangement.spacedBy(dims.paddingSmall)
    ) {
        repeat(count) {
            EventItemSkeleton()
        }
    }
}

/**
 * Placeholder for a single event row.
 * An animated placeholder for one event card.
 *
 * @param modifier Modifier for customisation
 */
@Composable
fun EventItemSkeleton(
    modifier: Modifier = Modifier
) {
    val dims = dimensions()
    val shimmer = shimmerBrush()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(dims.cornerRadius),
        elevation = CardDefaults.cardElevation(
            defaultElevation = dims.cardElevation
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(dims.paddingMedium),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Placeholder for the icon / time indicator
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )

            // Placeholder for the text content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Placeholder for the event title
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )

                // Placeholder for the description / time
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )
            }

            // Placeholder for the trailing icon / action
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )
        }
    }
}

/**
 * Placeholder for the calendar in month mode.
 * An animated placeholder for the month's day grid.
 *
 * @param modifier Modifier for customisation
 */
@Composable
fun CalendarMonthSkeleton(
    modifier: Modifier = Modifier
) {
    val dims = dimensions()
    val shimmer = shimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(dims.paddingMedium),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Placeholder for the weekday headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(7) {
                Box(
                    modifier = Modifier
                        .size(32.dp, 16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Placeholder for the day grid (six weeks)
        repeat(6) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(7) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(shimmer)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Placeholder for the day/week list with its hour slots.
 * Animated placeholders for the hour blocks.
 *
 * @param modifier Modifier for customisation
 * @param itemCount How many hour slots to draw
 */
@Composable
fun DayWeekViewSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 4
) {
    val dims = dimensions()
    val shimmer = shimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(dims.paddingMedium),
        verticalArrangement = Arrangement.spacedBy(dims.paddingMedium)
    ) {
        repeat(itemCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Placeholder for the time indicator
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmer)
                    )
                }

                // Placeholder for the event block
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(100.dp),
                    shape = RoundedCornerShape(dims.cornerRadius),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 2.dp
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(dims.paddingSmall),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmer)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmer)
                        )
                    }
                }
            }
        }
    }
}

/**
 * A shimmering placeholder of arbitrary size — the building block the other skeletons here are
 * made of, and the one a screen reaches for when it wants a shape this file does not already
 * have.
 *
 * The Russian KDoc this file carried — from when none of it was reachable from anywhere — was
 * translated with UX-2, which is what finally wired the skeletons up.
 *
 * @param modifier Sets the size and shape of the placeholder
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier
) {
    val shimmer = shimmerBrush()

    Box(
        modifier = modifier
            .background(shimmer)
    )
}

/**
 * Placeholder for the custody indicator card.
 * An animated placeholder for the parent indicator.
 *
 * @param modifier Modifier for customisation
 */
@Composable
fun CustodyIndicatorSkeleton(
    modifier: Modifier = Modifier
) {
    val dims = dimensions()
    val shimmer = shimmerBrush()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = dims.paddingMedium, vertical = dims.paddingSmall),
        shape = RoundedCornerShape(dims.cornerRadius),
        elevation = CardDefaults.cardElevation(
            defaultElevation = dims.cardElevation
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(dims.paddingMedium),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Placeholder for the icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )

            // Placeholder for the text
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmer)
            )
        }
    }
}

