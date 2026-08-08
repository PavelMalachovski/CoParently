package com.coparently.app.presentation.calendar

import org.junit.Test
import kotlin.test.assertEquals

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
}
