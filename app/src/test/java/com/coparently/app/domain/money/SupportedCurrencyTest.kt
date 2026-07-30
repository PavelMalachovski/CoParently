package com.coparently.app.domain.money

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for the region -> currency mapping behind the app's default currency. */
class SupportedCurrencyTest {

    @Test
    fun `maps the czech region to crowns`() {
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion("CZ"))
    }

    @Test
    fun `maps poland to zloty`() {
        assertEquals(SupportedCurrency.PLN, defaultCurrencyForRegion("PL"))
    }

    @Test
    fun `maps eurozone countries to euro`() {
        assertEquals(SupportedCurrency.EUR, defaultCurrencyForRegion("DE"))
        assertEquals(SupportedCurrency.EUR, defaultCurrencyForRegion("ES"))
        assertEquals(SupportedCurrency.EUR, defaultCurrencyForRegion("SK"))
    }

    @Test
    fun `accepts a lowercase country code`() {
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion("cz"))
    }

    @Test
    fun `falls back to crowns for an unknown or empty region`() {
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion("ZZ"))
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion(""))
    }

    @Test
    fun `resolves a stored code back to an entry`() {
        assertEquals(SupportedCurrency.EUR, SupportedCurrency.fromCode("EUR"))
        assertNull(SupportedCurrency.fromCode("XYZ"))
        assertNull(SupportedCurrency.fromCode(null))
    }
}
