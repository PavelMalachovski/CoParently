package com.coparently.app.presentation.expenses

import com.coparently.app.domain.model.ExpenseCategory
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The nine category fills the spending chart draws, derived from the theme rather than picked.
 *
 * **Kept free of Compose on purpose.** Everything here is arithmetic over packed ARGB ints, so
 * the palette can be unit tested — and it must be, because the numbers below were chosen by
 * running a colour-blindness validator rather than by looking at them. The Compose accessor is
 * `ExpenseCategory.sliceColor()` in `ExpenseCategoryLabel.kt`, one line thick.
 *
 * ## How the nine are chosen
 *
 * Each category takes a fixed slot — its enum ordinal — and each slot is one point in OKLCH:
 * the theme primary's own hue plus a fixed offset, at a fixed lightness and chroma. So the
 * palette *is* the theme's: change the primary and all nine rotate with it, which is what keeps
 * the chart from looking like a foreign object on the screen.
 *
 * The offsets are nine 40° steps around the wheel, but **assigned in steps of five slots**
 * (0°, 200°, 40°, 240°, …) rather than in order. Neighbouring categories therefore sit on
 * opposite sides of the wheel, which is what a pie needs: only adjacent slices touch, and
 * adjacent slices are what a reader compares. Lightness and chroma alternate on top of that,
 * because hue alone collapses under red-green colour blindness — two of nine hues always
 * become the same colour, and lightness is the axis that survives.
 *
 * **Light and dark are separately chosen, not flipped.** The dark steps sit in their own,
 * narrower lightness band, and their numbers were validated against the dark surface.
 *
 * ## What was measured, and what is not fixable
 *
 * Both palettes clear every check of the project's charting standard on the *adjacent* pairlist
 * — the one a pie is graded on, since only neighbouring arcs touch:
 *
 * | | worst adjacent pair, colour-blind | worst adjacent pair, full colour |
 * |---|---|---|
 * | light | ΔE 10.5 (protanopia) | ΔE 30.3 |
 * | dark | ΔE 11.1 (deuteranopia) | ΔE 25.0 |
 *
 * against a target of ΔE ≥ 8 and a floor of ≥ 15 respectively.
 *
 * **Nine categorical hues cannot be made distinguishable in every pairing, and no palette fixes
 * that** — it is a property of nine, not of these nine. Measured over *all* pairs rather than
 * adjacent ones, the worst pair falls to ΔE 6.9 in light and 1.3 in dark, and a search over
 * hundreds of lightness/chroma/ordering combinations could not lift both modes above the floor.
 * That is why this chart never asks anyone to tell two slices apart by colour: every slice has a
 * labelled legend entry, the table beneath carries the same figures as text, and the arcs are
 * separated by a visible gap. Colour here is a *pointer* between the chart and the table, not
 * the encoding. Do not "improve" this by adding a tenth category or by dropping the legend.
 *
 * If the theme's primary changes, `CategoryPaletteTest` fails — deliberately. It pins the exact
 * validated values, so a new primary forces the palette to be re-validated rather than shipping
 * unchecked.
 */
object CategoryPalette {

    /**
     * The colour for [category], as a packed ARGB int.
     *
     * @param category The category. Every one of the nine has a slot; the mapping is exhaustive
     *   by construction, since the slot *is* the ordinal.
     * @param seedArgb The theme's primary, whose hue anchors the whole wheel.
     * @param dark Whether the surface being drawn on is the dark one. Not a flip of the light
     *   value — a separately chosen step in the dark lightness band.
     */
    fun sliceArgb(category: ExpenseCategory, seedArgb: Int, dark: Boolean): Int {
        val slot = category.ordinal
        val lightness = if (dark) DARK_LIGHTNESS else LIGHT_LIGHTNESS
        val chroma = if (dark) DARK_CHROMA else LIGHT_CHROMA
        return oklchToArgb(
            lightness = lightness[slot % lightness.size],
            chroma = chroma[slot % chroma.size],
            hueDegrees = hueOf(seedArgb) + HUE_OFFSETS[slot]
        )
    }

    /** Hue offsets from the theme primary, by slot. Nine 40° steps, assigned five slots apart. */
    private val HUE_OFFSETS = intArrayOf(0, 200, 40, 240, 80, 280, 120, 320, 160)

    /** OKLCH lightness per slot, light surface. Inside the 0.43–0.77 band the standard allows. */
    private val LIGHT_LIGHTNESS = doubleArrayOf(0.50, 0.62, 0.74)

    /** OKLCH chroma per slot, light surface. Above the 0.10 floor, below which a hue reads grey. */
    private val LIGHT_CHROMA = doubleArrayOf(0.18, 0.12)

    /** OKLCH lightness per slot, dark surface. The dark band is narrower: 0.48–0.67. */
    private val DARK_LIGHTNESS = doubleArrayOf(0.54, 0.66)

    /** OKLCH chroma per slot, dark surface. Lower than light's: a dark surface needs less. */
    private val DARK_CHROMA = doubleArrayOf(0.13, 0.11)

    /** The OKLCH hue of a packed ARGB colour, in degrees. */
    private fun hueOf(argb: Int): Double {
        val r = toLinear((argb shr 16 and 0xFF) / 255.0)
        val g = toLinear((argb shr 8 and 0xFF) / 255.0)
        val b = toLinear((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return Math.toDegrees(atan2(bb, a))
    }

    /** A point in OKLCH as an opaque packed ARGB colour, clamped into sRGB. */
    private fun oklchToArgb(lightness: Double, chroma: Double, hueDegrees: Double): Int {
        val h = Math.toRadians(hueDegrees)
        val a = chroma * cos(h)
        val b = chroma * sin(h)
        val lCube = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val mCube = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val sCube = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return pack(
            red = 4.0767416621 * lCube - 3.3077115913 * mCube + 0.2309699292 * sCube,
            green = -1.2684380046 * lCube + 2.6097574011 * mCube - 0.3413193965 * sCube,
            blue = -0.0041960863 * lCube - 0.7034186147 * mCube + 1.7076147010 * sCube
        )
    }

    private fun pack(red: Double, green: Double, blue: Double): Int =
        (0xFF shl 24) or (toByte(red) shl 16) or (toByte(green) shl 8) or toByte(blue)

    private fun toLinear(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    private fun toByte(linear: Double): Int {
        val encoded = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055
        return (encoded * 255).roundToInt().coerceIn(0, 255)
    }
}
