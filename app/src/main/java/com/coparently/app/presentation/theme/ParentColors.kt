package com.coparently.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Parent identity colours, resolved for the theme that is actually being painted.
 *
 * `CoPlanlyColors.MomPink`/`DadBlue` are **fill-only** — neither clears 4.5:1 as a foreground
 * in either theme — so any screen that wants to write "Mom paid …" in pink has to reach for the
 * theme-aware `*Light`/`*Dark` partner instead. Three screens (home dashboard, calendar ribbon,
 * expenses split) now need that choice, and the luminance test behind it was already copy-pasted
 * into `MonthView` and `DayWeekView`. These helpers are the one place that decision lives.
 *
 * The test is on `MaterialTheme.colorScheme.surface.luminance()`, never `isSystemInDarkTheme()`:
 * the in-app theme setting can force light while the system is dark, and the colour has to
 * follow what is actually on screen.
 */
object ParentColors {

    /** True when the rendered theme is dark, whatever the system is set to. */
    val isDarkTheme: Boolean
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surface.luminance() < CoPlanlyColors.DARK_LUMINANCE_THRESHOLD

    /**
     * The parent's identity hue at full strength, for **fills only** — dots, bars, borders,
     * custody tints. Never use as a text colour; use [text] for that.
     *
     * @param parent `"mom"` or `"dad"`; anything else falls back to mom, matching the
     *   existing `parentColor` helpers this replaces.
     */
    fun fill(parent: String): Color =
        if (parent == "dad") CoPlanlyColors.DadBlue else CoPlanlyColors.MomPink

    /**
     * The parent's identity hue as a **text-grade** foreground for the current theme.
     *
     * @param parent `"mom"` or `"dad"`; anything else falls back to mom.
     */
    @Composable
    @ReadOnlyComposable
    fun text(parent: String): Color = if (parent == "dad") {
        if (isDarkTheme) CoPlanlyColors.DadBlueLight else CoPlanlyColors.DadBlueDark
    } else {
        if (isDarkTheme) CoPlanlyColors.MomPinkLight else CoPlanlyColors.MomPinkDark
    }

    /**
     * A soft container tint in the parent's hue, for chips and hero backgrounds that carry
     * [text]-coloured content.
     *
     * @param parent `"mom"` or `"dad"`; anything else falls back to mom.
     * @param alpha Tint strength; the default matches the calendar's custody wash so a chip on
     *   the dashboard and a day cell in the grid read as the same system.
     */
    fun container(parent: String, alpha: Float = CoPlanlyColors.CUSTODY_TINT_ALPHA): Color =
        fill(parent).copy(alpha = alpha)
}
