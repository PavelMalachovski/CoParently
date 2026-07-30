package com.coparently.app.data.repository

import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.money.defaultCurrencyForRegion
import com.coparently.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of PreferencesRepository.
 * Manages user preferences using EncryptedPreferences.
 */
@Singleton
class PreferencesRepositoryImpl @Inject constructor(
    private val encryptedPreferences: EncryptedPreferences
) : PreferencesRepository {

    private val _darkThemeFlow = MutableStateFlow<Boolean?>(null)
    private val _defaultCurrencyFlow = MutableStateFlow(resolveInitialCurrency())

    init {
        // Initialize with current value
        _darkThemeFlow.value = encryptedPreferences.getDarkTheme()
    }

    /**
     * Reads the stored currency, or resolves one from the device region and persists it so the
     * choice stays stable even if the device locale later changes.
     */
    private fun resolveInitialCurrency(): SupportedCurrency {
        SupportedCurrency.fromCode(encryptedPreferences.getDefaultCurrency())?.let { return it }
        val resolved = defaultCurrencyForRegion(Locale.getDefault().country)
        encryptedPreferences.putDefaultCurrency(resolved.code)
        return resolved
    }

    override fun getDarkThemeFlow(): Flow<Boolean?> {
        return _darkThemeFlow.asStateFlow()
    }

    override suspend fun getDarkTheme(): Boolean? {
        return encryptedPreferences.getDarkTheme()
    }

    override suspend fun setDarkTheme(isDarkTheme: Boolean) {
        encryptedPreferences.putDarkTheme(isDarkTheme)
        _darkThemeFlow.value = isDarkTheme
    }

    override suspend fun clearDarkTheme() {
        encryptedPreferences.clearDarkTheme()
        _darkThemeFlow.value = null
    }

    override fun getDefaultCurrencyFlow(): Flow<SupportedCurrency> =
        _defaultCurrencyFlow.asStateFlow()

    override suspend fun setDefaultCurrency(currency: SupportedCurrency) {
        encryptedPreferences.putDefaultCurrency(currency.code)
        _defaultCurrencyFlow.value = currency
    }
}

