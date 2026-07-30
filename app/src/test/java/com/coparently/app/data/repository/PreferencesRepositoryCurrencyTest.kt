package com.coparently.app.data.repository

import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.money.SupportedCurrency
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

/** Tests for the region-seeded default currency preference. */
class PreferencesRepositoryCurrencyTest {

    private fun prefs(storedCurrency: String?): EncryptedPreferences = mockk(relaxed = true) {
        every { getDarkTheme() } returns null
        every { getDefaultCurrency() } returns storedCurrency
    }

    private fun withLocale(language: String, country: String, block: () -> Unit) {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.Builder().setLanguage(language).setRegion(country).build())
        try {
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `seeds the currency from the device region when nothing is stored`() {
        withLocale("cs", "CZ") {
            val preferences = prefs(storedCurrency = null)
            val repository = PreferencesRepositoryImpl(preferences)

            runBlocking {
                assertEquals(SupportedCurrency.CZK, repository.getDefaultCurrencyFlow().first())
            }
            verify { preferences.putDefaultCurrency("CZK") }
        }
    }

    @Test
    fun `prefers the stored currency over the device region`() {
        withLocale("cs", "CZ") {
            val repository = PreferencesRepositoryImpl(prefs(storedCurrency = "EUR"))

            runBlocking {
                assertEquals(SupportedCurrency.EUR, repository.getDefaultCurrencyFlow().first())
            }
        }
    }

    @Test
    fun `ignores an unrecognised stored code and reseeds from the region`() {
        withLocale("pl", "PL") {
            val repository = PreferencesRepositoryImpl(prefs(storedCurrency = "XYZ"))

            runBlocking {
                assertEquals(SupportedCurrency.PLN, repository.getDefaultCurrencyFlow().first())
            }
        }
    }

    @Test
    fun `setting the currency persists it and updates the flow`() {
        withLocale("cs", "CZ") {
            val preferences = prefs(storedCurrency = null)
            val repository = PreferencesRepositoryImpl(preferences)

            runBlocking {
                repository.setDefaultCurrency(SupportedCurrency.EUR)
                assertEquals(SupportedCurrency.EUR, repository.getDefaultCurrencyFlow().first())
            }
            verify { preferences.putDefaultCurrency("EUR") }
        }
    }
}
