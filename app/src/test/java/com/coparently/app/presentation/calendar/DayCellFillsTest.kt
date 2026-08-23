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

}
