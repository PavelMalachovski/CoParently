package com.coparently.app.presentation.theme

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The parent colour, now that it is chosen rather than derived from the slot.
 *
 * The case that matters most is the collision: the entire purpose of a parent colour is telling
 * the two apart, so a family where both picked blue must not end up with a calendar in one hue.
 */
class ParentPaletteTest {

    @Test
    fun `an account that has not chosen keeps the colours the app always had`() {
        // An upgrade must change nothing until somebody actually picks something.
        val palette = ParentPalette.of(slot1Code = null, slot2Code = null)

        assertEquals(ParentColorChoice.PINK, palette.of("mom"))
        assertEquals(ParentColorChoice.BLUE, palette.of("dad"))
    }

    @Test
    fun `each parent's own choice is what gets drawn`() {
        val palette = ParentPalette.of(
            slot1Code = ParentColorChoice.ORANGE.storedCode,
            slot2Code = ParentColorChoice.PURPLE.storedCode
        )

        assertEquals(ParentColorChoice.ORANGE, palette.of("mom"))
        assertEquals(ParentColorChoice.PURPLE, palette.of("dad"))
    }

    @Test
    fun `two parents who chose the same colour are still drawn apart`() {
        // Two men both picking blue is the case this exists for — and so is one parent picking
        // the colour the other already had, which needs no coordination between two phones.
        val palette = ParentPalette.of(
            slot1Code = ParentColorChoice.BLUE.storedCode,
            slot2Code = ParentColorChoice.BLUE.storedCode
        )

        assertEquals(ParentColorChoice.BLUE, palette.of("mom"))
        assertEquals(ParentColorChoice.PINK, palette.of("dad"), "slot 2 moves to a free colour")
    }

    @Test
    fun `the collision is resolved the same way on both phones`() {
        // Slot 1 wins rather than "whoever chose first": `colorCode` carries no timestamp, and
        // an order invented from two independently-syncing documents would pick a different
        // winner on each device — which is two parents seeing the calendar differently.
        val onePhone = ParentPalette.of(
            ParentColorChoice.PURPLE.storedCode, ParentColorChoice.PURPLE.storedCode
        )
        val theOther = ParentPalette.of(
            ParentColorChoice.PURPLE.storedCode, ParentColorChoice.PURPLE.storedCode
        )

        assertEquals(onePhone, theOther)
    }

    @Test
    fun `a collision does not overwrite what the parent actually chose`() {
        // Only the drawing moves. Their setting still says purple, so it stops being overridden
        // the moment the other parent changes theirs.
        val stored = ParentColorChoice.PURPLE.storedCode
        val palette = ParentPalette.of(stored, stored)

        assertEquals(ParentColorChoice.PURPLE, ParentColorChoice.fromStored(stored))
        assertEquals(ParentColorChoice.PURPLE, palette.of("mom"))
    }

    @Test
    fun `a stored code is read whatever case it was written in`() {
        assertEquals(
            ParentColorChoice.PINK,
            ParentColorChoice.fromStored(ParentColorChoice.PINK.storedCode.lowercase())
        )
    }

    @Test
    fun `an unrecognised code is not silently mapped onto the default`() {
        // Null is the honest answer, and the caller decides the fallback. Quietly reading an
        // unknown colour as pink would make a co-parent's deliberate choice look like nobody's.
        assertNull(ParentColorChoice.fromStored("#123456"))
        assertNull(ParentColorChoice.fromStored(""))
        assertNull(ParentColorChoice.fromStored(null))
    }

    @Test
    fun `every choice has a distinct stored code`() {
        // The code is a stored value two devices compare; a duplicate would make two colours
        // indistinguishable on the wire.
        val codes = ParentColorChoice.entries.map { it.storedCode.uppercase() }

        assertEquals(codes.size, codes.toSet().size)
    }
}
