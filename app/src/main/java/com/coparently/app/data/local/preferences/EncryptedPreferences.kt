package com.coparently.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted SharedPreferences wrapper for secure storage.
 * Uses AndroidX Security Crypto library to encrypt sensitive data.
 */
@Singleton
class EncryptedPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "encrypted_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
     * Clears all stored preferences.
     */
    fun clear() {
        encryptedPreferences.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
    }
}

