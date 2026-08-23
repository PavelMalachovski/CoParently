package com.coparently.app.presentation.calendar

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The branching that decides a day cell's fill.
 *
 * Before this existed, the month cell picked exactly one background with custody ahead of the
 * weekend, and `CustodyModel.getCustodyFor` never returns null — so on any account with an
 * active custody model the weekend branch was unreachable and Saturday/Sunday were tinted only
 * in the grid rows that reach into a neighbouring month.
 */
class DayCellFillsTest {

    @Test
    fun `a weekend under a custody wash keeps its grey base`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.CUSTODY_MOM),
            DayCellFills.monthCell(
                isWeekend = true,
                isCurrentMonth = true,
                custody = "mom",
                previousCustody = "mom",
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `a weekend in a neighbouring month is grey with no overlay`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = true,
                isCurrentMonth = false,
                custody = "dad",
                previousCustody = "dad",
                isPublicHoliday = true
            )
        )
    }

    @Test
    fun `a weekday in a neighbouring month is plain surface`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = false,
                custody = "mom",
                previousCustody = "mom",
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `custody still beats a public holiday`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.CUSTODY_DAD),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = "dad",
                previousCustody = "dad",
                isPublicHoliday = true
            )
        )
    }

    @Test
    fun `a public holiday on a weekend tints over the grey base`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.PUBLIC_HOLIDAY),
            DayCellFills.monthCell(
                isWeekend = true,
                isCurrentMonth = true,
                custody = null,
                previousCustody = null,
                isPublicHoliday = true
            )
        )
    }

    @Test
    fun `a plain weekday has no overlay`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = null,
                previousCustody = null,
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `an unknown custody slot is not treated as a parent`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = "grandma",
                previousCustody = "grandma",
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `a week hour cell on a weekend keeps its grey base under custody`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.CUSTODY_MOM),
            DayCellFills.weekHourCell(isWeekend = true, isToday = false, custody = "mom")
        )
    }

    @Test
    fun `custody still beats today in the week view`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.CUSTODY_DAD),
            DayCellFills.weekHourCell(isWeekend = false, isToday = true, custody = "dad")
        )
    }

    @Test
    fun `today tints a weekend hour cell over the grey base`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.TODAY),
            DayCellFills.weekHourCell(isWeekend = true, isToday = true, custody = null)
        )
    }

    @Test
    fun `a day the child changes hands on is split, from yesterday's parent`() {
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "dad",
            previousCustody = "mom",
            isPublicHoliday = false
        )

        assertEquals(DayCellOverlay.CUSTODY_DAD, fill.overlay)
        assertEquals(DayCellOverlay.CUSTODY_MOM, fill.handoverFrom)
    }

    @Test
    fun `the day after a handover is not split`() {
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "dad",
            previousCustody = "dad",
            isPublicHoliday = false
        )

        assertNull(fill.handoverFrom)
    }

    @Test
    fun `a swap creates a split on the day it moves`() {
        // What the grid draws when an accepted override hands Thursday to the other parent: the
        // resolver answers "dad" for the swapped day while the pattern still answers "mom" for
        // the one before it, and the split follows from that alone.
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "dad",
            previousCustody = "mom",
            isPublicHoliday = false
        )

        assertEquals(DayCellOverlay.CUSTODY_MOM, fill.handoverFrom)
    }

    @Test
    fun `a swap removes the split it displaced`() {
        // Swapping the pattern's own switch day back to the parent who already had the day
        // before it leaves no handover there at all.
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "mom",
            previousCustody = "mom",
            isPublicHoliday = false
        )

        assertNull(fill.handoverFrom)
    }

    @Test
    fun `an unanswered day is never split, because an unknown is not a handover`() {
        assertNull(
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = "dad",
                previousCustody = null,
                isPublicHoliday = false
            ).handoverFrom
        )
        assertNull(
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = null,
                previousCustody = "dad",
                isPublicHoliday = false
            ).handoverFrom
        )
    }

    @Test
    fun `a neighbouring month's day is never split, matching its dimmed number`() {
        assertNull(
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = false,
                custody = "dad",
                previousCustody = "mom",
                isPublicHoliday = false
            ).handoverFrom
        )
    }

    @Test
    fun `a day a pending proposal would move draws as a preview over the agreed day`() {
        // The agreed pattern keeps its full strength underneath: `overlay` is still the parent
        // whose day it is *now*. A grid that showed only the proposal would tell a parent their
        // days had changed when they had not.
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "mom",
            previousCustody = "mom",
            isPublicHoliday = false,
            proposedCustody = "dad"
        )

        assertEquals(DayCellOverlay.CUSTODY_MOM, fill.overlay)
        assertEquals(DayCellOverlay.CUSTODY_DAD, fill.pendingProposalFor)
    }

    @Test
    fun `the same day with no proposal draws at full strength and nothing else`() {
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "mom",
            previousCustody = "mom",
            isPublicHoliday = false
        )

        assertEquals(DayCellOverlay.CUSTODY_MOM, fill.overlay)
        assertNull(fill.pendingProposalFor)
    }

    @Test
    fun `an accepted proposal is indistinguishable from an ordinary agreed day`() {
        // Accepting makes the proposal the agreed pattern and clears it from the document, so
        // the lookup answers "dad" and nothing is pending. Nothing about the cell should say a
        // proposal was ever involved.
        val accepted = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "dad",
            previousCustody = "dad",
            isPublicHoliday = false,
            proposedCustody = null
        )
        val ordinary = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "dad",
            previousCustody = "dad",
            isPublicHoliday = false
        )

        assertEquals(ordinary, accepted)
    }

    @Test
    fun `a day the proposal does not move is not marked`() {
        // A proposal is a whole pattern, and most of its days agree with the agreed one. Marking
        // those too would wash the month and say nothing.
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "mom",
            previousCustody = "mom",
            isPublicHoliday = false,
            proposedCustody = "mom"
        )

        assertNull(fill.pendingProposalFor)
    }

    @Test
    fun `a day nothing answers for is never previewed`() {
        // Previewing a move away from nothing would invent the arrangement it claims to preview.
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = null,
            previousCustody = null,
            isPublicHoliday = false,
            proposedCustody = "dad"
        )

        assertNull(fill.pendingProposalFor)
    }

    @Test
    fun `a neighbouring month's day is never previewed, matching its dimmed number`() {
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = false,
            custody = "mom",
            previousCustody = "mom",
            isPublicHoliday = false,
            proposedCustody = "dad"
        )

        assertNull(fill.pendingProposalFor)
    }

    @Test
    fun `the week view previews the same day the month grid does`() {
        // The week is the one view a parent renegotiating a specific week is looking at, and a
        // pending proposal has no other form in this layout — unlike a handover, which the week
        // already shows as the boundary between two runs of its custody band.
        val fill = DayCellFills.weekHourCell(
            isWeekend = false,
            isToday = false,
            custody = "mom",
            proposedCustody = "dad"
        )

        assertEquals(DayCellOverlay.CUSTODY_MOM, fill.overlay)
        assertEquals(DayCellOverlay.CUSTODY_DAD, fill.pendingProposalFor)
    }

    @Test
    fun `an unknown slot in a proposal is not treated as a parent`() {
        val fill = DayCellFills.monthCell(
            isWeekend = false,
            isCurrentMonth = true,
            custody = "mom",
            previousCustody = "mom",
            isPublicHoliday = false,
            proposedCustody = "guardian"
        )

        assertNull(fill.pendingProposalFor)
    }

}
