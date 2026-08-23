package com.coparently.app.presentation.expenses

import com.coparently.app.domain.model.ExpenseCategory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The nine slice colours, pinned to the exact values a colour-blindness validator approved.
 *
 * These are not aesthetic assertions. Each hex below was produced by the derivation in
 * [CategoryPalette] and then checked against the project's charting standard — lightness band,
 * chroma floor, colour-blind separation of adjacent pairs, full-colour separation of adjacent
 * pairs, and contrast against the surface. Pinning them means a change to the theme's primary,
 * or to the lightness/chroma steps, **fails here** rather than silently shipping a palette
 * nobody re-checked.
 *
 * If this test fails because the theme changed on purpose: re-run the validator on the new nine
 * and update the expectations with what it approved, rather than the other way round.
 */
class CategoryPaletteTest {

    /** The app theme's `primary` in light mode. */
    private val lightSeed = 0xFF4F46E5.toInt()

    /** The app theme's `primary` in dark mode. Its own value, not a tint of the light one. */
    private val darkSeed = 0xFFC2C1FF.toInt()

    @Test
    fun `the light palette is exactly the nine values the validator passed`() {
        // Reported: worst adjacent pair ΔE 10.5 under protanopia (target >= 8), ΔE 30.3 under
        // full colour vision (floor >= 15); all nine inside the 0.43-0.77 lightness band and
        // above the 0.10 chroma floor.
        assertEquals(
            listOf(
                "#4e50c6", "#818f30", "#d782f5", "#007746", "#d54d88",
                "#24c2c5", "#b22900", "#2b90c5", "#e99800"
            ),
            hexes(lightSeed, dark = false)
        )
    }

    @Test
    fun `the dark palette is separately chosen, not the light one flipped`() {
        // Reported: worst adjacent pair ΔE 11.1 under deuteranopia, ΔE 25.0 under full colour
        // vision, and — unlike light — every one of the nine clears 3:1 against the surface.
        assertEquals(
            listOf(
                "#6861b6", "#839e4f", "#945198", "#43a781", "#ab4964",
                "#13a5b2", "#aa5224", "#5399d1", "#916700"
            ),
            hexes(darkSeed, dark = true)
        )
    }

    @Test
    fun `all nine are distinct in both modes`() {
        assertEquals(9, hexes(lightSeed, dark = false).toSet().size)
        assertEquals(9, hexes(darkSeed, dark = true).toSet().size)
    }

    @Test
    fun `no slice may be a parent colour`() {
        // Pink and blue identify a *person* in this app, throughout. A "clothing" slice wearing
        // one would break the only colour rule the product has held from the start.
        val parentColours = setOf("#e91e63", "#1976d2", "#ffc1e3", "#c2185b", "#90caf9", "#0d47a1")
        val slices = hexes(lightSeed, dark = false) + hexes(darkSeed, dark = true)

        assertTrue(slices.none { it in parentColours }, "a slice collided with a parent colour")
    }

    @Test
    fun `a category keeps its colour when the ones around it disappear`() {
        // "Colour follows the entity, never its rank." The payer filter changes which categories
        // have anything in them, and a chart that repainted the survivors would tell a parent
        // that food became education. The slot is the ordinal, so this holds by construction —
        // pinned because a "sort by amount then colour by position" refactor would break it
        // without failing anything else.
        val everything = hexes(lightSeed, dark = false)
        val food = CategoryPalette.sliceArgb(ExpenseCategory.FOOD, lightSeed, dark = false)

        assertEquals(everything[ExpenseCategory.FOOD.ordinal], hex(food))
    }

    @Test
    fun `every category has a colour, whatever gets added later`() {
        // The slot is the ordinal, so a tenth category would silently reuse slot 0's lightness
        // and chroma at a new hue rather than failing to compile. That is the one thing this
        // design cannot enforce at the type level, so it is asserted here instead: nine, and
        // adding a tenth must come back through the validator.
        assertEquals(9, ExpenseCategory.entries.size)
        ExpenseCategory.entries.forEach { category ->
            val argb = CategoryPalette.sliceArgb(category, lightSeed, dark = false)
            assertEquals(0xFF, argb ushr 24 and 0xFF, "slices must be opaque")
        }
    }

    private fun hexes(seed: Int, dark: Boolean): List<String> =
        ExpenseCategory.entries.map { hex(CategoryPalette.sliceArgb(it, seed, dark)) }

    private fun hex(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)
}
