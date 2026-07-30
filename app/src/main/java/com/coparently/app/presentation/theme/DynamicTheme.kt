package com.coparently.app.presentation.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.coparently.app.data.preferences.CustomColorScheme
import com.coparently.app.data.preferences.ThemePreferences

/**
 * Dynamic theme for CoPlanly app with support for:
 * - Dark/Light mode
 * - Dynamic colors (Android 12+)
 * - Custom color schemes
 * - Font scaling for accessibility
 *
 * NOTE: not currently wired into the app. `MainActivity` renders through
 * [CoPlanlyTheme] in `Theme.kt`; this file and `AnimatedTheme.kt` are kept for a future
 * dynamic-theme path. Its colour slots are held in sync with `Theme.kt` deliberately — in
 * particular `secondary` stays neutral rather than Mom-pink, so whichever theme path the app
 * ends up on, the parent-identity rule holds.
 *
 * @param themePrefs Theme preferences containing all customization options
 * @param content The composable content to display with this theme
 */
@Composable
fun CoPlanlyDynamicTheme(
    themePrefs: ThemePreferences,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val colorScheme = when {
        themePrefs.useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (themePrefs.isDarkMode) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        themePrefs.customColors != null -> {
            createCustomColorScheme(themePrefs.customColors, themePrefs.isDarkMode)
        }
        themePrefs.isDarkMode -> {
            darkColorScheme(
                primary = CoPlanlyColors.DadBlueLight,
                onPrimary = CoPlanlyColors.DadBlueDark,
                primaryContainer = CoPlanlyColors.DadBlueDark,
                onPrimaryContainer = CoPlanlyColors.DadBlueLight,
                // Neutral, NOT Mom-pink — see CoPlanlyColors.NeutralSecondary. Feeds generic
                // Material selected states (FilterChips); parent pink/blue is applied directly.
                secondary = CoPlanlyColors.NeutralSecondaryDark,
                onSecondary = CoPlanlyColors.NeutralOnSecondaryDark,
                secondaryContainer = CoPlanlyColors.NeutralSecondaryContainerDark,
                onSecondaryContainer = CoPlanlyColors.NeutralSecondaryDark,
                tertiary = Color(0xFF66FF99),
                onTertiary = Color(0xFF003314),
                background = CoPlanlyColors.DarkBackground,
                onBackground = CoPlanlyColors.DarkOnBackground,
                surface = CoPlanlyColors.DarkSurface,
                onSurface = CoPlanlyColors.DarkOnSurface
            )
        }
        else -> {
            lightColorScheme(
                primary = CoPlanlyColors.BrandPrimary,
                onPrimary = Color.White,
                primaryContainer = CoPlanlyColors.DadBlueLight,
                onPrimaryContainer = CoPlanlyColors.DadBlueDark,
                // Neutral, NOT Mom-pink — see CoPlanlyColors.NeutralSecondary.
                secondary = CoPlanlyColors.NeutralSecondary,
                onSecondary = Color.White,
                secondaryContainer = CoPlanlyColors.NeutralSecondaryContainer,
                onSecondaryContainer = CoPlanlyColors.NeutralOnSecondaryContainer,
                tertiary = CoPlanlyColors.BrandAccent,
                onTertiary = Color.White,
                background = CoPlanlyColors.LightBackground,
                onBackground = CoPlanlyColors.LightOnBackground,
                surface = CoPlanlyColors.LightSurface,
                onSurface = CoPlanlyColors.LightOnSurface
            )
        }
    }

    // Apply font scale to typography
    val scaledTypography = remember(themePrefs.fontScale) {
        scaleTypography(Typography, themePrefs.fontScale)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = content
    )
}

/**
 * Creates a custom color scheme based on user preferences.
 *
 * @param customColors Custom color scheme with user-selected colors
 * @param isDarkMode Whether to create a dark or light color scheme
 * @return ColorScheme with custom colors applied
 */
private fun createCustomColorScheme(
    customColors: CustomColorScheme,
    isDarkMode: Boolean
): ColorScheme {
    return if (isDarkMode) {
        darkColorScheme(
            primary = customColors.primary,
            secondary = customColors.secondary,
            tertiary = customColors.tertiary,
            background = CoPlanlyColors.DarkBackground,
            surface = CoPlanlyColors.DarkSurface,
            onBackground = CoPlanlyColors.DarkOnBackground,
            onSurface = CoPlanlyColors.DarkOnSurface
        )
    } else {
        lightColorScheme(
            primary = customColors.primary,
            secondary = customColors.secondary,
            tertiary = customColors.tertiary,
            background = CoPlanlyColors.LightBackground,
            surface = CoPlanlyColors.LightSurface,
            onBackground = CoPlanlyColors.LightOnBackground,
            onSurface = CoPlanlyColors.LightOnSurface
        )
    }
}

/**
 * Scales typography based on font scale preference.
 *
 * @param typography Base typography to scale
 * @param fontScale Scale factor (1.0 = normal, <1.0 = smaller, >1.0 = larger)
 * @return Scaled typography
 */
private fun scaleTypography(
    typography: androidx.compose.material3.Typography,
    fontScale: Float
): androidx.compose.material3.Typography {
    val scale = fontScale.coerceIn(0.8f, 2.0f)

    return androidx.compose.material3.Typography(
        displayLarge = scaleTextStyle(typography.displayLarge, scale),
        displayMedium = scaleTextStyle(typography.displayMedium, scale),
        displaySmall = scaleTextStyle(typography.displaySmall, scale),
        headlineLarge = scaleTextStyle(typography.headlineLarge, scale),
        headlineMedium = scaleTextStyle(typography.headlineMedium, scale),
        headlineSmall = scaleTextStyle(typography.headlineSmall, scale),
        titleLarge = scaleTextStyle(typography.titleLarge, scale),
        titleMedium = scaleTextStyle(typography.titleMedium, scale),
        titleSmall = scaleTextStyle(typography.titleSmall, scale),
        bodyLarge = scaleTextStyle(typography.bodyLarge, scale),
        bodyMedium = scaleTextStyle(typography.bodyMedium, scale),
        bodySmall = scaleTextStyle(typography.bodySmall, scale),
        labelLarge = scaleTextStyle(typography.labelLarge, scale),
        labelMedium = scaleTextStyle(typography.labelMedium, scale),
        labelSmall = scaleTextStyle(typography.labelSmall, scale)
    )
}

/**
 * Scales a single text style.
 *
 * @param textStyle Base text style to scale
 * @param scale Scale factor
 * @return Scaled text style
 */
private fun scaleTextStyle(textStyle: TextStyle, scale: Float): TextStyle {
    return textStyle.copy(
        fontSize = textStyle.fontSize * scale,
        lineHeight = textStyle.lineHeight * scale
    )
}
