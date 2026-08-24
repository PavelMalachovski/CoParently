package com.coparently.app.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
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
 * Light color scheme for CoPlanly app.
 * Enhanced with better contrast and brand colors.
 */
private val LightColorScheme = lightColorScheme(
    primary = CoPlanlyColors.BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = CoPlanlyColors.BrandPrimaryContainer,
    onPrimaryContainer = CoPlanlyColors.BrandOnPrimaryContainer,
    // Neutral, NOT Mom-pink — see CoPlanlyColors.NeutralSecondary. Feeds generic
    // Material selected states (FilterChips); parent pink/blue is applied directly.
    secondary = CoPlanlyColors.NeutralSecondary,
    onSecondary = Color.White,
    secondaryContainer = CoPlanlyColors.NeutralSecondaryContainer,
    onSecondaryContainer = CoPlanlyColors.NeutralOnSecondaryContainer,
    tertiary = CoPlanlyColors.BrandAccent,
    onTertiary = Color.White,
    background = CoPlanlyColors.LightBackground,
    onBackground = CoPlanlyColors.LightOnBackground,
    surface = CoPlanlyColors.LightSurface,
    onSurface = CoPlanlyColors.LightOnSurface,
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC8C5D0),
    scrim = Color.Black,
    inverseSurface = CoPlanlyColors.DarkSurface,
    inverseOnSurface = CoPlanlyColors.DarkOnSurface,
    inversePrimary = Color(0xFFC2C1FF),
    surfaceDim = Color(0xFFDCD9E0),
    surfaceBright = CoPlanlyColors.LightBackground,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F4FA),
    surfaceContainer = Color(0xFFF0EEF5),
    surfaceContainerHigh = Color(0xFFEBE8F0),
    surfaceContainerHighest = Color(0xFFE5E2EA)
)

/**
 * Dark color scheme for CoPlanly app.
 * Enhanced with better contrast and brand colors.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC2C1FF),
    onPrimary = Color(0xFF201F60),
    primaryContainer = Color(0xFF373678),
    onPrimaryContainer = CoPlanlyColors.BrandPrimaryContainer,
    // Neutral, NOT Mom-pink — see CoPlanlyColors.NeutralSecondary.
    secondary = CoPlanlyColors.NeutralSecondaryDark,
    onSecondary = CoPlanlyColors.NeutralOnSecondaryDark,
    secondaryContainer = CoPlanlyColors.NeutralSecondaryContainerDark,
    onSecondaryContainer = CoPlanlyColors.NeutralSecondaryContainer,
    tertiary = Color(0xFF6EE7B7),
    onTertiary = Color(0xFF003824),
    background = CoPlanlyColors.DarkBackground,
    onBackground = CoPlanlyColors.DarkOnBackground,
    surface = CoPlanlyColors.DarkSurface,
    onSurface = CoPlanlyColors.DarkOnSurface,
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF47464F),
    scrim = Color.Black,
    inverseSurface = CoPlanlyColors.LightSurface,
    inverseOnSurface = CoPlanlyColors.LightOnSurface,
    inversePrimary = CoPlanlyColors.BrandPrimary,
    surfaceDim = CoPlanlyColors.DarkBackground,
    surfaceBright = Color(0xFF39383F),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = CoPlanlyColors.DarkSurface,
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF29292F),
    surfaceContainerHighest = Color(0xFF34333A)
)

/**
 * Local composition for theme state.
 */
val LocalThemeState = staticCompositionLocalOf { false }

/**
 * Material 3 theme for CoPlanly app.
 * Supports both light and dark themes with enhanced color schemes,
 * and responsive design based on window size.
 *
 * @param darkTheme Whether to use dark theme (defaults to system setting)
 * @param dynamicColor Whether to use dynamic colors (Android 12+)
 * @param windowSizeClass Window size class for responsive dimensions (optional)
 * @param content The composable content to display with this theme
 */
@Composable
fun CoPlanlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled by default for brand consistency
    windowSizeClass: WindowSizeClass? = null,
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

    // Dimensions come from the caller's window size class when one is given — previews and
    // tests do that — and otherwise from `adaptiveDimensions()`, which works the size class out
    // for itself and folds in font scale and touch exploration.
    //
    // The fallback used to be `compactDimensions`, and **no caller ever passed a size class**:
    // `MainActivity` and `QRScannerActivity` both call `CoPlanlyTheme(darkTheme = …)`. So every
    // device resolved to phone padding — tablets and unfolded folds included — and
    // `adaptiveDimensions()`, the only code in the project that reads `fontScale` or
    // `isTouchExplorationEnabled`, was never called by anything. Two features were switched off
    // by a default argument.
    //
    // Outside an Activity (a Compose preview) it degrades to exactly the old behaviour, because
    // there is no window to measure.
    val dimensions = windowSizeClass?.getDimensions() ?: adaptiveDimensions()

    // Configure system UI appearance using EdgeToEdge API
    // This is the modern approach recommended by Google (replaces Accompanist)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Set transparent status and navigation bars for edge-to-edge experience
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()

            // Configure system bar icons based on theme
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme

            // Note: For full edge-to-edge experience, ensure:
            // 1. enableEdgeToEdge() is called in MainActivity.onCreate()
            // 2. Scaffold with appropriate padding for system bars
        }
    }

    // Provide both theme state and dimensions through CompositionLocal
    CompositionLocalProvider(
        LocalThemeState provides darkTheme,
        LocalDimensions provides dimensions
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = CoPlanlyShapes,
            content = content
        )
    }
}

