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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light color scheme for CoParently app.
 */
private val LightColorScheme = lightColorScheme(
    primary = CoParentlyColors.DadBlue,
    secondary = CoParentlyColors.MomPink,
    tertiary = CoParentlyColors.EventGray
)

/**
 * Dark color scheme for CoParently app.
 */
private val DarkColorScheme = darkColorScheme(
    primary = CoParentlyColors.DadBlue,
    secondary = CoParentlyColors.MomPink,
    tertiary = CoParentlyColors.EventGray
)

/**
 * Material 3 theme for CoParently app.
 * Supports both light and dark themes.
 *
 * @param darkTheme Whether to use dark theme (defaults to system setting)
 * @param dynamicColor Whether to use dynamic colors (Android 12+)
 * @param content The composable content to display with this theme
 */
@Composable
fun CoParentlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

