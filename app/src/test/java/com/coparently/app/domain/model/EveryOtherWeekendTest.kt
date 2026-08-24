package com.coparently.app.domain.model

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `výhradní péče se stykem` — the preset MON-6 added.
 *
 * The three patterns beside it split the fortnight roughly in half, so a transposed index shows
 * up immediately. This one gives twelve days to one parent and two to the other, and getting it
 * backwards produces a schedule that is *also* perfectly plausible — the same shape, the other
 * way round. Nothing on the screen would look broken; a parent would simply have handed over the
 * school days. So the assertions here are about **which** days, named as weekdays rather than as
 * indices, and about the count on each side.
 */
class EveryOtherWeekendTest {

    /** A Monday. The anchor every fourteen-day preset in this file assumes. */
    private val monday = LocalDate.of(2026, 8, 3)

    private fun pattern(momIsResident: Boolean = true) =
        CustodyModel.everyOtherWeekend(id = "t", startDate = monday, momIsResident = momIsResident)

    @Test
    fun `the anchor really is a Monday`() {
        // If this ever fails, every day-name assertion below is measuring something else.
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
    }

    @Test
    fun `the resident parent has twelve of the fourteen days`() {
        val model = pattern(momIsResident = true)

        assertEquals(14, model.patternDays)
        assertEquals(12, model.momDayIndices.size)
    }

    @Test
    fun `the contact parent has exactly the first Saturday and Sunday`() {
        val model = pattern(momIsResident = true)

        val contactDays = (0 until 14)
            .map { monday.plusDays(it.toLong()) }
            .filter { model.getCustodyFor(it) == "dad" }

        assertEquals(
            listOf(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9)),
            contactDays,
            "contact is the first weekend of the fortnight and nothing else"
        )
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), contactDays.map { it.dayOfWeek })
    }

    @Test
    fun `the second weekend stays with the resident parent`() {
        // The half that makes it *every other* weekend rather than every weekend.
        val model = pattern(momIsResident = true)

        assertEquals("mom", model.getCustodyFor(LocalDate.of(2026, 8, 15)))
        assertEquals("mom", model.getCustodyFor(LocalDate.of(2026, 8, 16)))
    }

    @Test
    fun `every school day belongs to the resident parent`() {
        val model = pattern(momIsResident = true)

        val schoolDays = (0 until 14)
            .map { monday.plusDays(it.toLong()) }
            .filter { it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }

        assertTrue(
            schoolDays.all { model.getCustodyFor(it) == "mom" },
            "a weekday handed to the contact parent means the switch was read backwards"
        )
    }

    @Test
    fun `the switch reverses who is resident, not which days are contact`() {
        val resident = pattern(momIsResident = true)
        val contact = pattern(momIsResident = false)

        // Same two contact days either way — what changes is whose they are.
        assertEquals(setOf(5, 6), contact.momDayIndices)
        assertEquals(setOf(5, 6), (0 until 14).toSet() - resident.momDayIndices)
    }

    @Test
    fun `the two settings are exact complements`() {
        val resident = pattern(momIsResident = true)
        val contact = pattern(momIsResident = false)

        assertEquals(contact.momDayIndices, resident.complemented().momDayIndices)
    }

    @Test
    fun `the fortnight repeats, so the fourth weekend is contact again`() {
        val model = pattern(momIsResident = true)

        // 8 Aug is contact; 22 Aug is fourteen days later.
        assertEquals("dad", model.getCustodyFor(LocalDate.of(2026, 8, 22)))
        assertEquals("dad", model.getCustodyFor(LocalDate.of(2026, 8, 23)))
        // And the one in between is not.
        assertEquals("mom", model.getCustodyFor(LocalDate.of(2026, 8, 15)))
    }

    @Test
    fun `dates before the anchor follow the same fortnight`() {
        // `getCustodyFor` normalises a negative offset; a schedule that only worked forwards
        // would show the wrong parent on every day already in the calendar.
        val model = pattern(momIsResident = true)

        assertEquals("dad", model.getCustodyFor(LocalDate.of(2026, 7, 25)))
        assertEquals("dad", model.getCustodyFor(LocalDate.of(2026, 7, 26)))
        assertEquals("mom", model.getCustodyFor(LocalDate.of(2026, 7, 27)))
    }

    @Test
    fun `it is not the same schedule as week on week off`() {
        // Both are fourteen-day patterns anchored on the same Monday, and `isEquivalentTo`
        // compares by outcome — so this is the check that the preset is a distinct arrangement
        // rather than a second name for one that already existed.
        val everyOtherWeekend = pattern(momIsResident = true)
        val weekOnWeekOff = CustodyModel.weekOnWeekOff(id = "w", startDate = monday, momFirst = true)

        assertFalse(everyOtherWeekend.isEquivalentTo(weekOnWeekOff))
    }

    @Test
    fun `the wire name round-trips`() {
        // Room and Firestore both store the string, and an unknown one silently degrades to
        // CUSTOM — which would leave the schedule intact but stop the picker showing what the
        // parent chose.
        val name = CustodyModelType.toString(CustodyModelType.EVERY_OTHER_WEEKEND)

        assertEquals("every_other_weekend", name)
        assertEquals(CustodyModelType.EVERY_OTHER_WEEKEND, CustodyModelType.fromString(name))
    }
}
