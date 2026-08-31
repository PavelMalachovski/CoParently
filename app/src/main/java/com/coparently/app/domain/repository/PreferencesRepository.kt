package com.coparently.app.domain.repository

import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.telemetry.TelemetryConsent
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing user preferences.
 * Abstracts the data layer for application settings.
 */
interface PreferencesRepository {
    /**
     * Gets the dark theme preference as a Flow.
     * Returns null if not set (use system default).
     *
     * @return Flow emitting true for dark theme, false for light theme, null for system default
     */
    fun getDarkThemeFlow(): Flow<Boolean?>

    /**
     * Gets the current dark theme preference.
     * Returns null if not set (use system default).
     *
     * @return True for dark theme, false for light theme, null for system default
     */
    suspend fun getDarkTheme(): Boolean?

    /**
     * Sets the dark theme preference.
     *
     * @param isDarkTheme True to enable dark theme, false to enable light theme
     */
    suspend fun setDarkTheme(isDarkTheme: Boolean)

    /**
     * Clears the dark theme preference (reverts to system default).
     */
    suspend fun clearDarkTheme()

    /**
     * Gets the app-wide default currency as a Flow. Never emits null — when nothing has been
     * stored the region's currency is resolved and persisted on first read.
     *
     * @return Flow emitting the current default currency
     */
    fun getDefaultCurrencyFlow(): Flow<SupportedCurrency>

    /**
     * Sets the app-wide default currency used to pre-fill new expenses.
     *
     * @param currency Currency to store as the default
     */
    suspend fun setDefaultCurrency(currency: SupportedCurrency)

    /**
     * The analytics and crash-reporting consent, as a Flow.
     *
     * Never emits null: an account that has not been asked emits
     * [TelemetryConsent.UNANSWERED], which is a real answer to "should the gate be shown" and
     * not an absence. The start-destination decision reads it, so it must be correct on its
     * first emission rather than after a round trip.
     *
     * @return Flow emitting the current consent
     */
    fun getTelemetryConsentFlow(): Flow<TelemetryConsent>

    /**
     * Records the answer. Applying it to the SDKs is
     * [com.coparently.app.data.telemetry.TelemetryConsentApplier]'s job, not the caller's — see
     * its KDoc for why there is exactly one place that calls the setters.
     *
     * @param consent What the user chose
     */
    suspend fun setTelemetryConsent(consent: TelemetryConsent)
}

