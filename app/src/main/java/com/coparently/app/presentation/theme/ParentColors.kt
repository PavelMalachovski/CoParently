package com.coparently.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Parent identity colours, resolved for the theme that is actually being painted.
 *
 * A colour identifies a *parent*, not a role: the app no longer shows the words Mom and Dad,
 * and `"mom"`/`"dad"` survive only as slot identifiers assigned by pairing. Which person holds
 * which slot is decided in `functions/index.js` (`assignSlots`) and shown by name everywhere
 * else — see `presentation/common/ParentLabels.kt`.
 *
 * **Which colour a slot draws in is no longer fixed.** Each parent chooses their own
 * ([ParentColorChoice]), and the family's two answers arrive here as a [ParentPalette]. The
 * default is the pink and blue the app has always used, so a call site that does not have the
 * parents to hand — and a family where nobody has chosen — looks exactly as it did.
 *
 * `CoPlanlyColors.MomPink`/`DadBlue` are **fill-only** — neither clears 4.5:1 as a foreground
 * in either theme — so any screen that wants to write a parent's name in pink has to reach for
 * the theme-aware `*Light`/`*Dark` partner instead. Three screens (home dashboard, calendar
 * ribbon, expenses split) need that choice, and the luminance test behind it was already
 * copy-pasted into `MonthView` and `DayWeekView`. These helpers are the one place that decision
 * lives.
 *
 * The saturation rule is unchanged: a custody day background is the hue at ~14% alpha, a chip
 * or dot is the same hue at full strength.
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
     * @param parent `"mom"` or `"dad"`; anything else falls back to slot 1.
     * @param palette The family's two chosen colours. Defaults to pink and blue, which is
     *   what a screen that has not resolved the parents draws — unchanged from before anyone
     *   could choose.
     */
    fun fill(parent: String, palette: ParentPalette = ParentPalette.Default): Color =
        palette.of(parent).fill

    /**
     * The parent's identity hue as a **text-grade** foreground for the current theme.
     *
     * @param parent `"mom"` or `"dad"`; anything else falls back to mom.
     */
    @Composable
    @ReadOnlyComposable
    fun text(parent: String, palette: ParentPalette = ParentPalette.Default): Color =
        palette.of(parent).let { if (isDarkTheme) it.light else it.dark }

    /**
     * A soft container tint in the parent's hue, for chips and hero backgrounds that carry
     * [text]-coloured content.
     *
     * @param parent `"mom"` or `"dad"`; anything else falls back to mom.
     * @param alpha Tint strength; the default matches the calendar's custody wash so a chip on
     *   the dashboard and a day cell in the grid read as the same system.
     */
    fun container(
        parent: String,
        alpha: Float = CoPlanlyColors.CUSTODY_TINT_ALPHA,
        palette: ParentPalette = ParentPalette.Default
    ): Color = fill(parent, palette).copy(alpha = alpha)
}
