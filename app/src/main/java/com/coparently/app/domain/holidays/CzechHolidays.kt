package com.coparently.app.domain.holidays

import java.time.LocalDate

/**
 * Czech public holidays and nationwide school vacations.
 *
 * Public holidays are computed for any year (Easter via [gregorianEasterSunday]). School
 * vacations follow the official MŠMT calendar; the nationwide periods (autumn, Christmas,
 * Easter, summer) are covered while the district-dependent spring break is intentionally
 * omitted — it is set per district and the app has no way to know which one a family is in.
 *
 * Since MON-13 this is *a* provider rather than *the* holiday calendar: it implements
 * [HolidayProvider] and is reached through [HolidayCountry], so a family outside Czechia is no
 * longer shown Czech holidays.
 */
object CzechHolidays : HolidayProvider {

    override val localLanguage: String = "cs"

    /**
     * Returns all public holidays for the given year.
     */
    override fun publicHolidays(year: Int): List<Holiday> {
        val easterSunday = gregorianEasterSunday(year)
        return listOf(
            cs(LocalDate.of(year, 1, 1), "New Year's Day", "Nový rok"),
            cs(easterSunday.minusDays(2), "Good Friday", "Velký pátek"),
            cs(easterSunday.plusDays(1), "Easter Monday", "Velikonoční pondělí"),
            cs(LocalDate.of(year, 5, 1), "Labour Day", "Svátek práce"),
            cs(LocalDate.of(year, 5, 8), "Victory Day", "Den vítězství"),
            cs(LocalDate.of(year, 7, 5), "Saints Cyril and Methodius Day", "Den slovanských věrozvěstů Cyrila a Metoděje"),
            cs(LocalDate.of(year, 7, 6), "Jan Hus Day", "Den upálení mistra Jana Husa"),
            cs(LocalDate.of(year, 9, 28), "Czech Statehood Day", "Den české státnosti"),
            cs(LocalDate.of(year, 10, 28), "Independent Czechoslovak State Day", "Den vzniku samostatného československého státu"),
            cs(LocalDate.of(year, 11, 17), "Freedom and Democracy Day", "Den boje za svobodu a demokracii"),
            cs(LocalDate.of(year, 12, 24), "Christmas Eve", "Štědrý den"),
            cs(LocalDate.of(year, 12, 25), "Christmas Day", "1. svátek vánoční"),
            cs(LocalDate.of(year, 12, 26), "St. Stephen's Day", "2. svátek vánoční")
        )
    }

    /**
     * Returns nationwide school vacation periods that overlap the given year.
     * Each period is a pair of (start date inclusive, end date inclusive) with names.
     */
    override fun schoolVacations(
        year: Int
    ): List<Pair<ClosedRange<LocalDate>, Pair<String, String>>> {
        val easterSunday = gregorianEasterSunday(year)
        // Easter school vacation: the Thursday before Good Friday
        val easterVacationStart = easterSunday.minusDays(3)

        return listOf(
            // Summer vacation (1 July – 31 August, fixed every year)
            LocalDate.of(year, 7, 1)..LocalDate.of(year, 8, 31) to
                ("Summer vacation" to "Hlavní prázdniny"),
            // Christmas vacation (approx. 23 Dec – 2 Jan; nationwide)
            LocalDate.of(year, 12, 23)..LocalDate.of(year, 12, 31) to
                ("Christmas vacation" to "Vánoční prázdniny"),
            LocalDate.of(year, 1, 1)..LocalDate.of(year, 1, 2) to
                ("Christmas vacation" to "Vánoční prázdniny"),
            // Autumn vacation (around 29–30 October, adjacent to 28 Oct holiday)
            LocalDate.of(year, 10, 29)..LocalDate.of(year, 10, 30) to
                ("Autumn vacation" to "Podzimní prázdniny"),
            // Easter vacation (Thursday before Good Friday)
            easterVacationStart..easterVacationStart to
                ("Easter vacation" to "Velikonoční prázdniny")
        )
    }

    /** A Czech public holiday, named in both languages the UI can pick between. */
    private fun cs(date: LocalDate, nameEn: String, nameCs: String) =
        Holiday(date, nameEn, nameCs, localLanguage = "cs")
}
