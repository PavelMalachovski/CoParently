package com.coparently.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted SharedPreferences wrapper for secure storage.
 *
 * Holds the Google OAuth **refresh token**, access token, ID token and the signed-in address
 * (see the `KEY_*` constants) — the refresh token being a credential that grants a bearer
 * standing access to the user's Google Calendar until it is revoked.
 *
 * **This never degrades to unencrypted on-disk storage, and the degradation it used to perform
 * is the reason this class is written the way it is.** Every failure branch used to end in
 * `context.getSharedPreferences("encrypted_prefs", MODE_PRIVATE)` — the *same file name* the
 * encrypted store uses — so a single `EncryptedSharedPreferences.create` failure wrote the
 * refresh token to `/data/data/<pkg>/shared_prefs/encrypted_prefs.xml` in clear text, and said
 * so only in a `Log.w` nobody reads. Two things made that permanent rather than momentary:
 *
 *  * the recovery deleted `encrypted_prefs.xml` via [Context.deleteFile], which resolves under
 *    `files/`, while SharedPreferences live under `shared_prefs/` — so the corrupt keyset was
 *    never actually removed and every later launch failed identically; and
 *  * the fallback shared the encrypted store's file, mixing AES256_SIV key names and
 *    ciphertext values with plain ones, so the store could not read its own earlier writes and
 *    the app simply re-issued the tokens and wrote those in clear text beside them.
 *
 * The keysets `EncryptedSharedPreferences` derives live inside that same preferences file, so
 * clearing the file is what a genuine recovery looks like: it costs the stored tokens (the user
 * signs in to Google Calendar again) and buys back an encrypted store. If even that fails, the
 * store is held [InMemorySharedPreferences] — the behaviour the old "ultimate fallback" comment
 * claimed but did not implement. Tokens then live for the process and no further, which is the
 * correct trade for a credential: an inconvenience is recoverable, a plaintext refresh token on
 * disk is not.
 */
@Singleton
class EncryptedPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val encryptedPreferences: SharedPreferences = createEncryptedStore(context)

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
     * Stores the unsent composer text for one conversation.
     *
     * An empty [text] removes the entry rather than storing a blank, so a thread the user
     * cleared does not keep a row forever.
     *
     * @param conversationId The thread the text was typed into.
     * @param text What is in the composer.
     */
    fun putChatDraft(conversationId: String, text: String) {
        val key = PreferenceKeys.CHAT_DRAFT_PREFIX + conversationId
        encryptedPreferences.edit().apply {
            if (text.isEmpty()) remove(key) else putString(key, text)
        }.apply()
    }

    /**
     * The unsent composer text for one conversation, or an empty string when there is none.
     *
     * @param conversationId The thread to read the draft of.
     */
    fun getChatDraft(conversationId: String): String =
        encryptedPreferences.getString(PreferenceKeys.CHAT_DRAFT_PREFIX + conversationId, null)
            .orEmpty()

    /**
     * Records the agreed split of a shared expense, as slot 1's share in basis points.
     *
     * @param basisPoints `0..10000`.
     */
    fun putSplitRatioBasisPoints(basisPoints: Int) {
        encryptedPreferences.edit()
            .putInt(PreferenceKeys.SPLIT_RATIO_BASIS_POINTS, basisPoints)
            .apply()
    }

    /**
     * The agreed split, or null when the family has never agreed one.
     *
     * Null rather than "half each": "we never agreed" and "we agreed on half each" are different
     * facts, and only the caller knows which fallback belongs to it.
     */
    fun getSplitRatioBasisPoints(): Int? =
        encryptedPreferences.getInt(PreferenceKeys.SPLIT_RATIO_BASIS_POINTS, -1)
            .takeIf { it >= 0 }

    /**
     * Records which slot the cached share belongs to, so it can be re-anchored later.
     *
     * @param slot `"mom"` or `"dad"`, or null to forget — which is what a paired write does,
     *   because from then on the pair's document is the record and the cache merely mirrors it.
     */
    fun putSplitRatioSlot(slot: String?) {
        encryptedPreferences.edit()
            .apply {
                if (slot == null) {
                    remove(PreferenceKeys.SPLIT_RATIO_SLOT)
                } else {
                    putString(PreferenceKeys.SPLIT_RATIO_SLOT, slot)
                }
            }
            .apply()
    }

    /** The slot the cached share was captured under, or null when it was never recorded. */
    fun getSplitRatioSlot(): String? =
        encryptedPreferences.getString(PreferenceKeys.SPLIT_RATIO_SLOT, null)

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
     * Clears stored preferences — **except** the per-user parent-slot markers
     * ([PreferenceKeys.PARENT_SLOT_MARKER_PREFIX]).
     *
     * This is no longer literally "all", and that is deliberate, not an oversight: this method
     * is reached from the app's own Sign out (`SettingsScreen`'s confirm dialog runs
     * `SyncViewModel.signOut` — which calls this via `CredentialManagerService`/
     * `GoogleSignInService` — immediately before `AuthStateViewModel.signOut`) as well as from
     * disconnecting Google Calendar alone (`onCalendarSignOut`). Neither of those touches Room,
     * where `users`/`events` rows deliberately survive sign-out so a returning parent's history
     * is still there. Before this exemption, signing out during the window between a
     * server-side slot backfill and this device's next sync wiped the one record
     * (`ParentSlotMigrator`'s marker) that tells the next sync a device has local history
     * needing a re-stamp — so a device with a full local history stamped in the old slot would
     * take the "nothing to do" branch on sign back in, permanently, the same damage this
     * marker exists to prevent, reintroduced through where it lives.
     *
     * A Google Calendar disconnect has no business wiping a parent-slot marker in the first
     * place — the two describe unrelated things — so this exemption is arguably correct on its
     * own terms, not merely a patch. See [PreferenceKeys.PARENT_SLOT_MARKER_PREFIX]'s own KDoc
     * for why the marker is keyed per-UID: it is what stops a second account that later signs
     * in on this device from reading the first account's now-surviving marker as its own.
     */
    fun clear() {
        val preservedMarkers = encryptedPreferences.all
            .filterKeys { it.startsWith(PreferenceKeys.PARENT_SLOT_MARKER_PREFIX) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }

        encryptedPreferences.edit().apply {
            clear()
            preservedMarkers.forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    companion object {
        private const val TAG = "EncryptedPreferences"

        /**
         * The preferences file. It holds both the encrypted entries and the Tink keysets
         * `EncryptedSharedPreferences` wraps with the master key, which is why clearing this one
         * file is a complete reset of the store.
         */
        private const val STORE_NAME = "encrypted_prefs"

        /**
         * Opens the encrypted store, recovering once from a corrupt keyset and falling back to
         * memory rather than to plaintext.
         *
         * A `create` failure is not exotic: the master key is invalidated by a lock-screen
         * change on several OEM builds, by a Keystore restore, and by a Play Services update
         * that rotates the provider. What follows the failure is what matters, and the only two
         * acceptable outcomes are an encrypted store or no store on disk at all.
         */
        private fun createEncryptedStore(context: Context): SharedPreferences {
            openEncrypted(context)?.let { return it }

            // The keyset inside the file no longer matches the master key that wraps it. Remove
            // the file — data and keysets together — and let `create` mint a fresh keyset. This
            // discards the stored Google tokens, so the user re-authorises Calendar; nothing
            // else in here is not re-derivable.
            Log.w(TAG, "Encrypted store unreadable; clearing it and re-creating")
            deleteStore(context)

            openEncrypted(context)?.let { return it }

            // Both attempts failed, so the device cannot give us an encrypted store at all.
            // Keeping the tokens in memory means they are gone at process death and never
            // touch the disk — see this class's KDoc for why plaintext is not the alternative.
            Log.e(TAG, "No encrypted store available; keeping preferences in memory only")
            return InMemorySharedPreferences()
        }

        /** The encrypted store, or null when it cannot be opened for any reason. */
        private fun openEncrypted(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                STORE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encrypted preferences could not be opened", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "Encrypted preferences could not be opened", e)
            null
        }

        /**
         * Removes the preferences file, keysets included.
         *
         * `deleteSharedPreferences` is API 24+ and this module is minSdk 26, so the older
         * clear-and-hope path is not needed. A `clear()` on the open store would not do:
         * the failure being recovered from is one where the store cannot be opened.
         */
        private fun deleteStore(context: Context) {
            try {
                context.deleteSharedPreferences(STORE_NAME)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Deliberately broad: whatever goes wrong here, the next step is the same —
                // try to open the store again, and hold it in memory if that fails too.
                Log.e(TAG, "Could not delete the corrupt encrypted store", e)
            }
        }

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

