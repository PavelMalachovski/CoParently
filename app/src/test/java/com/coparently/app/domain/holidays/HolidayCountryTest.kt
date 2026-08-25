package com.coparently.app.domain.holidays

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The country a parent lives in, and the holiday calendar that follows (MON-13).
 *
 * MVP 1 asked for "holidays and vacations by country" and shipped one country with no field
 * anywhere to say otherwise, so a German or Ukrainian family got Czech public holidays. The two
 * properties that matter here are that an upgrade changes nothing, and that a country the app has
 * no table for draws **no** holidays rather than somebody else's.
 */
class HolidayCountryTest {

    @Test
    fun `an account that predates the field reads as Czechia`() {
        // Which is what the v32-33 migration stamps, and what those accounts were already being
        // shown. An upgrade must not move anybody's holidays.
        assertEquals(HolidayCountry.CZECHIA, HolidayCountry.Default)
        assertEquals(HolidayCountry.CZECHIA, HolidayCountry.fromCode("CZ"))
    }

    @Test
    fun `a stored code is read whatever case or padding it arrives in`() {
        assertEquals(HolidayCountry.GERMANY, HolidayCountry.fromCode("de"))
        assertEquals(HolidayCountry.UKRAINE, HolidayCountry.fromCode(" UA "))
    }

    @Test
    fun `an unknown code falls back rather than failing`() {
        // A newer build's country read by an older one, or a blank row. Every call site draws a
        // calendar and has to draw something.
        assertEquals(HolidayCountry.Default, HolidayCountry.fromCode("XX"))
        assertEquals(HolidayCountry.Default, HolidayCountry.fromCode(""))
        assertEquals(HolidayCountry.Default, HolidayCountry.fromCode(null))
    }

    @Test
    fun `every country has a distinct stored code`() {
        // The code is a stored value two devices compare; a duplicate would make two countries
        // indistinguishable on the wire.
        val codes = HolidayCountry.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `Czechia is the one country whose holidays the app actually computes`() {
        // Not an aspiration: `hasHolidays` is what the picker renders its note from, so this test
        // failing means either a table landed (delete the assertion, the picker follows) or one
        // was claimed without being written.
        assertTrue(HolidayCountry.CZECHIA.hasHolidays)
        assertNotNull(HolidayCountry.CZECHIA.provider)

        HolidayCountry.entries.filter { it != HolidayCountry.CZECHIA }.forEach {
            assertNull(it.provider, "${it.code} claims a holiday table it does not have")
        }
    }

    @Test
    fun `a country with no table draws no holidays at all`() {
        // The whole point. Drawing another country's was the defect; drawing none is honest, and
        // the picker says so on the row.
        val germany = HolidayCountry.GERMANY

        assertNull(germany.provider)
    }

    @Test
    fun `the Czech provider still answers through the shared interface`() {
        val holidays = HolidayCountry.CZECHIA.provider!!
            .holidaysInRange(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26))

        assertEquals(3, holidays.size)
        assertEquals("cs", holidays.values.first().localLanguage)
    }

    @Test
    fun `a public holiday wins over a school vacation covering the same day`() {
        // 24-26 December are both, and a parent looking at the grid wants to be told it is
        // Christmas rather than that school is out. The precedence lives on the interface now,
        // so no future provider can get it subtly different.
        val christmas = HolidayCountry.CZECHIA.provider!!.holidayFor(LocalDate.of(2026, 12, 25))

        assertNotNull(christmas)
        assertEquals(false, christmas.isSchoolVacation)
    }
}
