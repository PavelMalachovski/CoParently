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
 * Создает анимированный градиент для эффекта shimmer (мерцания).
 *
 * @return Brush с анимированным горизонтальным градиентом
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
 * Skeleton Loading для списка событий.
 * Показывает несколько анимированных плейсхолдеров событий.
 *
 * @param modifier Модификатор для кастомизации
 * @param count Количество скелетонов для отображения
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
 * Skeleton Loading для одного события.
 * Отображает анимированный плейсхолдер для карточки события.
 *
 * @param modifier Модификатор для кастомизации
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
            // Skeleton для иконки/индикатора времени
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )

            // Skeleton для текстового контента
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Skeleton для заголовка события
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )

                // Skeleton для описания/времени
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmer)
                )
            }

            // Skeleton для дополнительной иконки/действия
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
 * Skeleton Loading для календаря в режиме месяца.
 * Отображает анимированную сетку дней месяца.
 *
 * @param modifier Модификатор для кастомизации
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
        // Skeleton для заголовков дней недели
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

        // Skeleton для сетки дней (6 недель)
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
 * Skeleton Loading для списка дня/недели с временными слотами.
 * Отображает анимированные временные блоки событий.
 *
 * @param modifier Модификатор для кастомизации
 * @param itemCount Количество временных слотов для отображения
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
                // Skeleton для индикатора времени
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

                // Skeleton для блока события
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
 * The rest of this file still carries Russian KDoc from when none of it was reachable from
 * anywhere; the project's rule is English, and translating it belongs with wiring the remaining
 * skeletons up (UX-2), not with this one.
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
 * Skeleton Loading для карточки custody indicator.
 * Отображает анимированный плейсхолдер для индикатора родителя.
 *
 * @param modifier Модификатор для кастомизации
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
            // Skeleton для иконки
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )

            // Skeleton для текста
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

