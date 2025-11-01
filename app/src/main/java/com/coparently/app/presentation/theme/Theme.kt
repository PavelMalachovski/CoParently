package com.coparently.app.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light color scheme for CoParently app.
 * Enhanced with better contrast and brand colors.
 */
private val LightColorScheme = lightColorScheme(
    primary = CoParentlyColors.BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = CoParentlyColors.DadBlueLight,
    onPrimaryContainer = CoParentlyColors.DadBlueDark,
    secondary = CoParentlyColors.MomPink,
    onSecondary = Color.White,
    secondaryContainer = CoParentlyColors.MomPinkLight,
    onSecondaryContainer = CoParentlyColors.MomPinkDark,
    tertiary = CoParentlyColors.BrandAccent,
    onTertiary = Color.White,
    background = CoParentlyColors.LightBackground,
    onBackground = CoParentlyColors.LightOnBackground,
    surface = CoParentlyColors.LightSurface,
    onSurface = CoParentlyColors.LightOnSurface,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF616161),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFFBDBDBD),
    scrim = Color.Black,
    inverseSurface = CoParentlyColors.DarkSurface,
    inverseOnSurface = CoParentlyColors.DarkOnSurface,
    inversePrimary = CoParentlyColors.DadBlueLight,
    surfaceDim = Color(0xFFD9D9D9),
    surfaceBright = CoParentlyColors.LightSurface,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE9E9E9),
    surfaceContainerHighest = Color(0xFFE3E3E3)
)

/**
 * Dark color scheme for CoParently app.
 * Enhanced with better contrast and brand colors.
 */
private val DarkColorScheme = darkColorScheme(
    primary = CoParentlyColors.DadBlueLight,
    onPrimary = CoParentlyColors.DadBlueDark,
    primaryContainer = CoParentlyColors.DadBlueDark,
    onPrimaryContainer = CoParentlyColors.DadBlueLight,
    secondary = CoParentlyColors.MomPinkLight,
    onSecondary = CoParentlyColors.MomPinkDark,
    secondaryContainer = CoParentlyColors.MomPinkDark,
    onSecondaryContainer = CoParentlyColors.MomPinkLight,
    tertiary = Color(0xFF66FF99),
    onTertiary = Color(0xFF003314),
    background = CoParentlyColors.DarkBackground,
    onBackground = CoParentlyColors.DarkOnBackground,
    surface = CoParentlyColors.DarkSurface,
    onSurface = CoParentlyColors.DarkOnSurface,
    surfaceVariant = Color(0xFF424242),
    onSurfaceVariant = Color(0xFFC2C2C2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF909090),
    outlineVariant = Color(0xFF424242),
    scrim = Color.Black,
    inverseSurface = CoParentlyColors.LightSurface,
    inverseOnSurface = CoParentlyColors.LightOnSurface,
    inversePrimary = CoParentlyColors.DadBlueDark,
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF383838),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF2E2E2E),
    surfaceContainerHighest = Color(0xFF383838)
)

/**
 * Local composition for theme state.
 */
val LocalThemeState = staticCompositionLocalOf { false }

/**
 * Material 3 theme for CoParently app.
 * Supports both light and dark themes with enhanced color schemes.
 *
 * @param darkTheme Whether to use dark theme (defaults to system setting)
 * @param dynamicColor Whether to use dynamic colors (Android 12+)
 * @param content The composable content to display with this theme
 */
@Composable
fun CoParentlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled by default for brand consistency
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalThemeState provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

