package com.coparently.app.domain.holidays

/**
 * The country a parent lives in, and the holiday calendar that follows from it (MON-13).
 *
 * MVP 1 asked for "holidays and vacations by country" and shipped **one** country. There was no
 * country field anywhere in the app — no picker, no stored value, not even a constant — so
 * `CalendarScreen` called `CzechHolidays` directly and a family in Germany, Russia or Ukraine got
 * Czech public holidays on their grid, in an app that ships in five languages. This is the stored
 * answer to that question.
 *
 * ## What each entry promises, and what it does not
 *
 * [provider] is **null for a country whose holiday table the app does not have yet**, and the
 * picker says so on the row. That is deliberate, and it is the honest half of this change: the
 * alternative — offering Germany and silently showing Czech holidays, or nothing at all with no
 * explanation — is design rule 8's forbidden affordance, a control that looks like it works.
 * A country with no provider shows **no** public holidays, which is strictly better than showing
 * another country's.
 *
 * They are unimplemented for a reason worth recording rather than hiding: a holiday table is a
 * set of user-visible facts, and the wrong date is worse than no date. Czechia's is computed,
 * tested and has been in production. The rest have to be authored against a source, and one
 * search while writing this already turned up a change that memory would have got wrong —
 * Slovakia's 2024–2026 consolidation packages moved several days off the non-working list while
 * leaving their formal names in place. Each country wants that check before its table lands.
 *
 * ## Why a country and not just "which holidays to show"
 *
 * A country is a fact about the user that other features will want — currency defaults, which
 * legal text applies, whether a school-import integration is even available (MON-8). Storing
 * "holiday calendar = none" would answer today's question and lose that.
 *
 * @property code ISO 3166-1 alpha-2, the value stored on the profile. Stable: two devices
 *   compare it, so an entry is never re-lettered.
 * @property provider This country's holiday calendar, or null when the app has no table for it.
 */
enum class HolidayCountry(
    val code: String,
    val provider: HolidayProvider?
) {
    /** The only country whose holidays the app actually computes, and the default. */
    CZECHIA("CZ", CzechHolidays),

    SLOVAKIA("SK", null),

    GERMANY("DE", null),

    AUSTRIA("AT", null),

    UKRAINE("UA", null),

    RUSSIA("RU", null),

    /** Anywhere else. Public holidays are not shown; everything else works unchanged. */
    OTHER("ZZ", null);

    /** Whether picking this country actually puts holidays on the grid. */
    val hasHolidays: Boolean get() = provider != null

    companion object {

        /**
         * What an account that has never chosen is treated as.
         *
         * Czechia, and the same value the v32→v33 migration stamps on every row that already
         * exists. The app is Czech-first and its one holiday table is the Czech one, so this is
         * both the honest default and the one that changes nothing for anybody already using it.
         */
        val Default: HolidayCountry = CZECHIA

        /**
         * The country a stored code names, falling back to [Default].
         *
         * A fallback rather than null: every call site draws a calendar and has to draw
         * *something*, and an unrecognised code — a newer build's country read by an older one —
         * is better answered with the default than with a crash or an empty grid. Case is
         * normalised because the value has been written by a migration default, by a picker and
         * by whatever a co-parent's build sends.
         */
        fun fromCode(code: String?): HolidayCountry {
            val normalized = code?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.code == normalized } ?: Default
        }
    }
}
