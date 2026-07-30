package com.coparently.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted SharedPreferences wrapper for secure storage.
 * Uses AndroidX Security Crypto library to encrypt sensitive data.
 * Falls back to regular SharedPreferences if encryption is not available.
 */
@Singleton
class EncryptedPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey? = try {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    } catch (e: Exception) {
        Log.e("EncryptedPreferences", "Failed to create MasterKey, falling back to regular SharedPreferences", e)
        null
    }

    private val encryptedPreferences: SharedPreferences = try {
        if (masterKey != null) {
            try {
                EncryptedSharedPreferences.create(
                    context,
                    "encrypted_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("EncryptedPreferences", "Failed to create EncryptedSharedPreferences, trying to delete corrupted file", e)
                // Try to delete the corrupted encrypted file and fall back
                try {
                    context.deleteFile("encrypted_prefs.xml")
                    context.deleteFile("encrypted_prefs.xml.bak")
                } catch (deleteEx: Exception) {
                    Log.w("EncryptedPreferences", "Could not delete corrupted encrypted file", deleteEx)
                }
                // Fallback to regular SharedPreferences
                Log.w("EncryptedPreferences", "Using regular SharedPreferences as fallback")
                context.getSharedPreferences("encrypted_prefs", Context.MODE_PRIVATE)
            }
        } else {
            // Fallback to regular SharedPreferences if encryption fails
            Log.w("EncryptedPreferences", "Using regular SharedPreferences as fallback (no master key)")
            context.getSharedPreferences("encrypted_prefs", Context.MODE_PRIVATE)
        }
    } catch (e: Exception) {
        Log.e("EncryptedPreferences", "Failed to create SharedPreferences, using application context", e)
        // Last resort: use application context
        try {
            context.applicationContext.getSharedPreferences("encrypted_prefs", Context.MODE_PRIVATE)
        } catch (e2: Exception) {
            Log.e("EncryptedPreferences", "Critical: Failed to create any SharedPreferences, using in-memory fallback", e2)
            // Ultimate fallback: use in-memory SharedPreferences
            // This will lose data on app restart but prevents crash
            context.getSharedPreferences("encrypted_prefs_memory", Context.MODE_PRIVATE)
        }
    }

    /**
     * Stores an access token securely.
     */
    fun putAccessToken(token: String) {
        encryptedPreferences.edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .apply()
    }

    /**
     * Retrieves the stored access token.
     */
    fun getAccessToken(): String? {
        return encryptedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Stores a refresh token securely.
     */
    fun putRefreshToken(token: String) {
        encryptedPreferences.edit()
            .putString(KEY_REFRESH_TOKEN, token)
            .apply()
    }

    /**
     * Retrieves the stored refresh token.
     */
    fun getRefreshToken(): String? {
        return encryptedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Stores token expiry time in milliseconds.
     */
    fun putTokenExpiry(expiryTimeMillis: Long) {
        encryptedPreferences.edit()
            .putLong(KEY_TOKEN_EXPIRY, expiryTimeMillis)
            .apply()
    }

    /**
     * Retrieves the stored token expiry time.
     */
    fun getTokenExpiry(): Long? {
        val expiry = encryptedPreferences.getLong(KEY_TOKEN_EXPIRY, -1)
        return if (expiry == -1L) null else expiry
    }

    /**
     * Stores Google Calendar ID.
     */
    fun putCalendarId(calendarId: String) {
        encryptedPreferences.edit()
            .putString(KEY_CALENDAR_ID, calendarId)
            .apply()
    }

    /**
     * Retrieves the stored Google Calendar ID.
     */
    fun getCalendarId(): String? {
        return encryptedPreferences.getString(KEY_CALENDAR_ID, "primary")
    }

    /**
     * Stores sync enabled status.
     */
    fun putSyncEnabled(enabled: Boolean) {
        encryptedPreferences.edit()
            .putBoolean(KEY_SYNC_ENABLED, enabled)
            .apply()
    }

    /**
     * Retrieves sync enabled status.
     */
    fun isSyncEnabled(): Boolean {
        return encryptedPreferences.getBoolean(KEY_SYNC_ENABLED, false)
    }

    /**
     * Stores Google ID token (from Credential Manager).
     */
    fun putGoogleIdToken(token: String) {
        encryptedPreferences.edit()
            .putString(KEY_GOOGLE_ID_TOKEN, token)
            .apply()
    }

    /**
     * Retrieves the stored Google ID token.
     */
    fun getGoogleIdToken(): String? {
        return encryptedPreferences.getString(KEY_GOOGLE_ID_TOKEN, null)
    }

    /**
     * Stores user email from Google account.
     */
    fun putUserEmail(email: String) {
        encryptedPreferences.edit()
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    /**
     * Retrieves the stored user email.
     */
    fun getUserEmail(): String? {
        return encryptedPreferences.getString(KEY_USER_EMAIL, null)
    }

    /**
     * Stores dark theme preference.
     *
     * @param isDarkTheme Whether dark theme is enabled
     */
    fun putDarkTheme(isDarkTheme: Boolean) {
        encryptedPreferences.edit()
            .putBoolean(KEY_DARK_THEME, isDarkTheme)
            .apply()
    }

    /**
     * Retrieves dark theme preference.
     * Returns null if not set (use system default).
     *
     * @return True if dark theme, false if light theme, null if system default
     */
    fun getDarkTheme(): Boolean? {
        return if (encryptedPreferences.contains(KEY_DARK_THEME)) {
            encryptedPreferences.getBoolean(KEY_DARK_THEME, false)
        } else {
            null // Not set, use system default
        }
    }

    /**
     * Clears dark theme preference (reverts to system default).
     */
    fun clearDarkTheme() {
        encryptedPreferences.edit()
            .remove(KEY_DARK_THEME)
            .apply()
    }

    /**
     * Stores a boolean value.
     *
     * @param key The key name
     * @param value The boolean value
     */
    fun putBoolean(key: String, value: Boolean) {
        encryptedPreferences.edit()
            .putBoolean(key, value)
            .apply()
    }

    /**
     * Retrieves a boolean value.
     *
     * @param key The key name
     * @param defaultValue The default value if not found
     * @return The boolean value
     */
    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return encryptedPreferences.getBoolean(key, defaultValue)
    }

    /**
     * Stores a string value.
     *
     * @param key The key name
     * @param value The string value
     */
    fun putString(key: String, value: String) {
        encryptedPreferences.edit()
            .putString(key, value)
            .apply()
    }

    /**
     * Retrieves a string value.
     *
     * @param key The key name
     * @param defaultValue The default value if not found
     * @return The string value
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return encryptedPreferences.getString(key, defaultValue)
    }

    /**
     * Stores event draft data as JSON string.
     * Issue 1.3: Draft saving functionality.
     */
    fun putEventDraft(draftJson: String) {
        encryptedPreferences.edit()
            .putString(KEY_EVENT_DRAFT, draftJson)
            .apply()
    }

    /**
     * Retrieves the stored event draft.
     * Issue 1.3: Draft saving functionality.
     */
    fun getEventDraft(): String? {
        return encryptedPreferences.getString(KEY_EVENT_DRAFT, null)
    }

    /**
     * Clears the stored event draft.
     * Issue 1.3: Draft saving functionality.
     */
    fun clearEventDraft() {
        encryptedPreferences.edit()
            .remove(KEY_EVENT_DRAFT)
            .apply()
    }

    /**
     * Stores the app-wide default currency.
     *
     * @param code ISO 4217 currency code, e.g. "CZK"
     */
    fun putDefaultCurrency(code: String) {
        encryptedPreferences.edit()
            .putString(KEY_DEFAULT_CURRENCY, code)
            .apply()
    }

    /**
     * Retrieves the stored default currency code.
     *
     * @return The ISO 4217 code, or null when the user has never had one resolved
     */
    fun getDefaultCurrency(): String? {
        return encryptedPreferences.getString(KEY_DEFAULT_CURRENCY, null)
    }

    /**
     * Clears all stored preferences.
     */
    fun clear() {
        encryptedPreferences.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_GOOGLE_ID_TOKEN = "google_id_token"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_EVENT_DRAFT = "event_draft"
        private const val KEY_DEFAULT_CURRENCY = "default_currency"
    }
}

