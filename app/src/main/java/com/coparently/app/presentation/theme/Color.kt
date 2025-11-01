package com.coparently.app.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Color palette for CoParently app.
 * Defines colors for parents (mom = pink, dad = blue) and app theme.
 * Enhanced with brand colors for Stage 5.
 */
object CoParentlyColors {
    // Parent colors - Brand colors
    val MomPink = Color(0xFFFF4081) // Material Pink 500
    val DadBlue = Color(0xFF2196F3) // Material Blue 500

    // Additional color variations
    val MomPinkLight = Color(0xFFFFC1E3)
    val MomPinkDark = Color(0xFFE91E63)
    val DadBlueLight = Color(0xFF90CAF9)
    val DadBlueDark = Color(0xFF1976D2)

    // Neutral colors
    val EventGray = Color(0xFF9E9E9E) // Gray 500

    // Brand colors for app theme
    val BrandPrimary = Color(0xFF6366F1) // Indigo 500
    val BrandSecondary = Color(0xFF8B5CF6) // Purple 500
    val BrandAccent = Color(0xFF10B981) // Green 500

    // Light theme colors
    val LightBackground = Color(0xFFFAFAFA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightOnSurface = Color(0xFF1F1F1F)
    val LightOnBackground = Color(0xFF1F1F1F)

    // Dark theme colors
    val DarkBackground = Color(0xFF121212)
    val DarkSurface = Color(0xFF1E1E1E)
    val DarkOnSurface = Color(0xFFE0E0E0)
    val DarkOnBackground = Color(0xFFE0E0E0)

    // Custody indicator colors
    val CustodyIndicatorActive = Color(0xFFFFD700) // Gold
    val CustodyIndicatorInactive = Color(0xFF757575) // Gray
}

