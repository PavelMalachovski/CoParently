package com.coparently.app.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Color palette for CoPlanly app.
 * Defines colors for parents (mom = pink, dad = blue) and app theme.
 *
 * Every token below records **two** contrast ratios — against white (light theme) and against
 * [DarkSurface] `#1B1B21` (dark theme) — because the app ships and is used in dark, where a
 * ratio measured on white says nothing. Ratios are computed with the WCAG 2.x relative
 * luminance formula.
 *
 * Each token also states whether it is **text-grade** (may be used as a foreground colour, so
 * it must clear 4.5:1 against the surface it sits on) or **fill-only** (tints, borders, dots —
 * WCAG 1.4.3 does not apply, and using it as text is a bug). No single hue clears 4.5:1
 * against both white and `#1B1B21`, which is why the text-grade entries come in theme-aware
 * pairs. Pick between a pair with `MaterialTheme.colorScheme.surface.luminance()`, never with
 * `isSystemInDarkTheme()` — the in-app theme can differ from the system one.
 */
object CoPlanlyColors {
    /**
     * Alpha for the custody background tint on calendar cells (month) and columns (week/day).
     * Kept as one constant so the views cannot drift apart again: week/day used to tint at
     * 0.03f, which over [DarkSurface] is about one RGB step and made custody invisible.
     */
    const val CUSTODY_TINT_ALPHA = 0.14f

    /**
     * Threshold for `MaterialTheme.colorScheme.surface.luminance()` when choosing between the
     * light- and dark-theme member of a text-grade colour pair. Below this the rendered theme
     * is dark. Use this rather than `isSystemInDarkTheme()`: the app can force light while the
     * system is dark, and the colour must follow what is actually painted.
     */
    const val DARK_LUMINANCE_THRESHOLD = 0.5f

    // Parent colors — product-level identity (mom = pink, dad = blue), do not repurpose.
    // FILL-ONLY: custody tints, identity borders, legend dots. Neither clears 4.5:1 as text
    // in either theme, so never use these as a foreground colour — see the *Text pairs below.
    val MomPink = Color(0xFFE91E63) // Material Pink 700 - 4.35:1 on white / 3.94:1 on DarkSurface
    val DadBlue = Color(0xFF1976D2) // Material Blue 700 - 4.60:1 on white / 3.72:1 on DarkSurface

    // Parent colors as TEXT-GRADE foregrounds, paired by theme.
    // Light theme reads the Dark variants; dark theme reads the Light variants.
    val MomPinkLight = Color(0xFFFFC1E3) // Pink 100 - 1.50:1 on white / 11.42:1 on DarkSurface (text-grade in dark)
    val MomPinkDark = Color(0xFFC2185B) // Pink 800 - 5.87:1 on white / 2.92:1 on DarkSurface (text-grade in light)
    val DadBlueLight = Color(0xFF90CAF9) // Blue 200 - 1.75:1 on white / 9.79:1 on DarkSurface (text-grade in dark)
    val DadBlueDark = Color(0xFF0D47A1) // Blue 900 - 8.63:1 on white / 1.99:1 on DarkSurface (text-grade in light)

    // Solid parent fills carrying WHITE text (month event chips). Solid MomPink is only
    // 4.35:1 under white text and fails AA, so the chip fill uses the Dark variants:
    // white on MomPinkDark is 5.87:1, white on DadBlueDark is 8.63:1.
    val MomChipFill = MomPinkDark
    val DadChipFill = DadBlueDark

    // Neutral colors
    // UNUSED as of 2026-07-30 — no references in app/src/main.
    val EventGray = Color(0xFF616161) // Gray 700 - 6.19:1 on white / 2.77:1 on DarkSurface

    // Secondary (neutral) tonal family — used by generic Material components such as
    // the selected state of FilterChips. Deliberately an indigo-tinted neutral, NOT
    // Mom-pink: pink/blue are reserved for parent identity and are applied via the
    // MomPink/DadBlue values above, never through the theme's `secondary` slot.
    // muted indigo-gray - 6.45:1 on white / 2.66:1 on DarkSurface (light theme only)
    val NeutralSecondary = Color(0xFF5B5D72)
    val NeutralSecondaryContainer = Color(0xFFE1E0F7) // light indigo tint - fill-only
    val NeutralOnSecondaryContainer = Color(0xFF191A2C) // deep indigo for container text
    val NeutralSecondaryDark = Color(0xFFC5C4DD) // light tone for dark theme - 10.05:1 on DarkSurface
    val NeutralOnSecondaryDark = Color(0xFF2E2F42) // container text on dark
    val NeutralSecondaryContainerDark = Color(0xFF434559) // dark indigo container - fill-only

    // Brand colors for app theme
    val BrandPrimary = Color(0xFF4F46E5) // Indigo 600 - 6.29:1 on white / 2.73:1 on DarkSurface (light theme only)
    val BrandPrimaryContainer = Color(0xFFE2E0FF) // Soft indigo container - fill-only
    val BrandOnPrimaryContainer = Color(0xFF1A1650) // Deep indigo for container text
    val BrandSecondary = Color(0xFF7C3AED) // Purple 600 - 5.70:1 on white / 3.01:1 on DarkSurface (light theme only)
    val BrandAccent = Color(0xFF059669) // Green 600 - 3.77:1 on white / 4.55:1 on DarkSurface (dark theme only)

    // Light theme colors - subtle indigo-tinted neutrals for a modern tonal look
    val LightBackground = Color(0xFFFCFBFF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightOnSurface = Color(0xFF1B1B21) // 15.9:1 on white - text-grade in light theme
    val LightOnBackground = Color(0xFF1B1B21)

    // Dark theme colors - tinted dark neutrals instead of pure gray
    val DarkBackground = Color(0xFF131318)
    val DarkSurface = Color(0xFF1B1B21)

    // 14.33:1 on DarkBackground, 13.26:1 on DarkSurface - text-grade in dark theme
    val DarkOnSurface = Color(0xFFE4E1E9)
    val DarkOnBackground = Color(0xFFE4E1E9)

    // Custody indicator colors
    // UNUSED as of 2026-07-30 — no references in app/src/main.
    val CustodyIndicatorActive = Color(0xFFF59E0B) // Amber 500 - 2.15:1 on white / 7.98:1 on DarkSurface
    val CustodyIndicatorInactive = Color(0xFF616161) // Gray 700 - 6.19:1 on white / 2.77:1 on DarkSurface

    // Weekend background colors - Saturday/Sunday, applied to every cell in the grid as the
    // base a custody, holiday or today tint is then drawn over. Fill-only: never used as text.
    // Neutral rather than the warm cream/olive they used to be, and one value per theme rather
    // than a per-call-site alpha, so the month and week grids read as one system.
    val WeekendBackgroundLight = Color(0xFFECECEF) // Neutral light grey, one step off white
    val WeekendBackgroundDark = Color(0xFF2A2A31) // Neutral dark grey, one step off DarkSurface

    // Holiday colors - public holidays and school vacations (Czech calendar).
    // Both are used as day-number TEXT, so each needs a theme-aware partner: no single red
    // or teal clears 4.5:1 against both white and DarkSurface.
    val HolidayRed = Color(0xFFD32F2F) // Red 700 - 4.98:1 on white / 3.44:1 on DarkSurface (light theme)
    val HolidayRedDark = Color(0xFFEF5350) // Red 400 - 3.49:1 on white / 4.92:1 on DarkSurface (dark theme)
    val VacationTint = Color(0xFF26A69A) // Teal 400 - 3.00:1 on white / 5.72:1 on DarkSurface (dark theme)
    val VacationTintLight = Color(0xFF00796B) // Teal 700 - 5.32:1 on white / 3.22:1 on DarkSurface (light theme)
}

