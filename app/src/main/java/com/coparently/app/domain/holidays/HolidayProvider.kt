package com.coparently.app.domain.holidays

import java.time.LocalDate

/**
 * A public holiday or school vacation day shown in the calendar.
 *
 * @property date The concrete date.
 * @property nameEn English display name — the fallback every locale falls back to.
 * @property nameLocal The holiday's name in the language of the country it belongs to. This was
 *   `nameCs` while Czechia was the only country the app knew; the field kept its meaning and
 *   lost the assumption.
 * @property localLanguage The ISO 639-1 code [nameLocal] is written in (`"cs"`, `"de"`, …).
 *   A screen shows [nameLocal] when the device language matches and [nameEn] otherwise, which
 *   is the rule `MonthView` already applied — hardcoded to Czech.
 * @property isSchoolVacation True for school vacation days, false for public holidays.
 */
data class Holiday(
    val date: LocalDate,
    val nameEn: String,
    val nameLocal: String,
    val localLanguage: String,
    val isSchoolVacation: Boolean = false
)

/**
 * One country's public holidays and school vacations.
 *
 * Extracted from `CzechHolidays` in MON-13, which is where the app's single hardcoded country
 * lived: `CalendarScreen` called `CzechHolidays.holidaysInRange` directly, at the calendar's one
 * holiday call site, and the stored preference is still named "show Czech holidays". A German,
 * Russian or Ukrainian user got Czech public holidays with no way to say otherwise, in an app
 * that ships in five languages.
 *
 * A provider supplies the two lists; the lookups are derived here so no implementation can get
 * the precedence or the range walk subtly different from another's.
 *
 * **Public holidays and school vacations are separate questions, and a country may answer only
 * the first.** A public holiday is national and computable; a school calendar often is not —
 * Germany's and Austria's are set per state, and Czechia's own district-dependent spring break
 * is left out for exactly that reason. Returning an empty list from [schoolVacations] is a
 * legitimate answer and must not be read as "this country has no school holidays".
 */
interface HolidayProvider {

    /** Every public holiday falling in [year]. */
    fun publicHolidays(year: Int): List<Holiday>

    /**
     * Nationwide school vacation periods overlapping [year], as (range, (English, local) names).
     *
     * Empty when the country's school calendar is regional or not known to the app.
     */
    fun schoolVacations(year: Int): List<Pair<ClosedRange<LocalDate>, Pair<String, String>>>

    /** The ISO 639-1 language [Holiday.nameLocal] is written in for this country. */
    val localLanguage: String

    /**
     * The holiday for [date] — a public holiday first, then a school vacation — or null on an
     * ordinary day.
     *
     * The precedence is not arbitrary: 24–26 December are both public holidays and inside the
     * school break, and a parent looking at the grid wants to be told it is Christmas rather
     * than that school is out.
     */
    fun holidayFor(date: LocalDate): Holiday? {
        publicHolidays(date.year).firstOrNull { it.date == date }?.let { return it }

        schoolVacations(date.year).firstOrNull { (range, _) -> date in range }?.let { (_, names) ->
            return Holiday(
                date = date,
                nameEn = names.first,
                nameLocal = names.second,
                localLanguage = localLanguage,
                isSchoolVacation = true
            )
        }
        return null
    }

    /**
     * Every holiday between [start] and [end] inclusive, keyed by date for the grid's lookup.
     */
    fun holidaysInRange(start: LocalDate, end: LocalDate): Map<LocalDate, Holiday> {
        val result = mutableMapOf<LocalDate, Holiday>()
        var date = start
        while (!date.isAfter(end)) {
            holidayFor(date)?.let { result[date] = it }
            date = date.plusDays(1)
        }
        return result
    }
}

/**
 * Easter Sunday, shared by every Western-Christian country's provider.
 *
 * The anonymous Gregorian computus, lifted out of `CzechHolidays` unchanged. It is here rather
 * than copied per provider because Good Friday, Easter Monday, Ascension, Whit Monday and Corpus
 * Christi are all offsets from it, and a country whose Easter drifted from its neighbour's by a
 * day would be a bug nobody would look for.
 *
 * Orthodox Easter is a *different* computation (Julian reckoning) and does not belong in this
 * function. A provider that needs it should say so in its own file rather than adding a flag
 * here.
 */
fun gregorianEasterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, month, day)
}
