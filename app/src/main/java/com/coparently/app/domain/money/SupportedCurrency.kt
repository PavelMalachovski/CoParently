package com.coparently.app.domain.money

/**
 * Currencies the app offers when logging an expense or a budget.
 *
 * @property code ISO 4217 code stored on [com.coparently.app.domain.model.Expense]
 * @property symbol Short symbol for compact UI where a full code would not fit
 */
enum class SupportedCurrency(val code: String, val symbol: String) {
    CZK("CZK", "Kč"),
    EUR("EUR", "€"),
    PLN("PLN", "zł"),
    USD("USD", "$"),
    GBP("GBP", "£"),
    CHF("CHF", "CHF"),
    HUF("HUF", "Ft"),
    SEK("SEK", "kr"),
    DKK("DKK", "kr"),
    NOK("NOK", "kr");

    companion object {
        /** Used when nothing is stored and the region is unknown; the app's primary market. */
        val DEFAULT: SupportedCurrency = CZK

        /**
         * Resolves a stored ISO code back to an entry.
         *
         * @param code ISO 4217 code, or null when nothing has been stored yet
         * @return The matching entry, or null when the code is unknown
         */
        fun fromCode(code: String?): SupportedCurrency? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Picks a sensible currency for a device region, e.g. Czechia gets crowns and Poland zloty.
 *
 * @param countryCode ISO 3166-1 alpha-2 country code; case-insensitive, may be empty
 * @return The region's currency, or [SupportedCurrency.DEFAULT] when it is not mapped
 */
fun defaultCurrencyForRegion(countryCode: String): SupportedCurrency =
    when (countryCode.uppercase()) {
        "CZ" -> SupportedCurrency.CZK
        "PL" -> SupportedCurrency.PLN
        "DE", "ES", "AT", "SK", "FR", "IT", "NL", "BE", "PT", "IE", "FI", "GR" ->
            SupportedCurrency.EUR
        "US" -> SupportedCurrency.USD
        "GB" -> SupportedCurrency.GBP
        "CH" -> SupportedCurrency.CHF
        "HU" -> SupportedCurrency.HUF
        "SE" -> SupportedCurrency.SEK
        "DK" -> SupportedCurrency.DKK
        "NO" -> SupportedCurrency.NOK
        else -> SupportedCurrency.DEFAULT
    }
