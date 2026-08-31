package com.coparently.app.data.local.security

import android.content.Context
import android.util.Log
import com.coparently.app.data.security.EncryptionException
import com.coparently.app.data.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passphrase that opens the encrypted Room database (SEC-2).
 *
 * Minted once with [SecureRandom], wrapped by [EncryptionManager] — AES-256-GCM under an Android
 * Keystore key, hardware-backed where the device offers it — and the wrapped form kept in ordinary
 * preferences. The ciphertext on disk is worthless without the Keystore key, and the Keystore key
 * cannot be exported off the device, which is the whole property being bought here.
 *
 * **Why not `EncryptedPreferences`,** which the app already has and which is also Keystore-backed:
 * because of what it does when it cannot open its store. It clears the file and mints a fresh
 * keyset, and failing that keeps entries in memory only — exactly right for the Google refresh
 * token it was written for, where losing the credential costs one re-authorisation. Applied to
 * this passphrase the same behaviour would quietly hand out a *different* key on the next launch,
 * and the database encrypted with the old one would be unopenable with nothing to say why.
 * [EncryptionManager] has no such store to lose: the key lives in the Keystore itself.
 *
 * **The passphrase is text, not bytes, and it is hexadecimal on purpose.** It is used in two
 * places that take it differently — SQLCipher's `SupportOpenHelperFactory` takes bytes, and the
 * `ATTACH DATABASE ... KEY` statement of the migration takes a SQL string literal — and hex is the
 * one encoding that survives both without quoting or escaping. Base64 would have been shorter and
 * would have put `/` and `+` inside a SQL literal for no gain.
 */
@Singleton
class DatabaseKey @Inject constructor(
    @ApplicationContext context: Context,
    private val encryptionManager: EncryptionManager
) {

    private val preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    /**
     * The stored passphrase, or null when there is none to recover.
     *
     * Null means one of two things and the caller must tell them apart by looking at the database
     * file: on a fresh install nothing was ever stored, while beside an encrypted database it
     * means the Keystore key is gone and the file can never be read again. See
     * [SqlCipherMigration.next], which is given exactly this as a boolean.
     */
    fun recover(): String? {
        val wrapped = preferences.getString(KEY_WRAPPED_PASSPHRASE, null) ?: return null
        return try {
            encryptionManager.decrypt(wrapped)
        } catch (e: EncryptionException) {
            // The Keystore key that wrapped it is gone: Keystore corruption, or a lock-screen
            // change on the OEM builds where that invalidates keys. Nothing here can undo it.
            Log.e(TAG, "Database passphrase could not be unwrapped; the database is unreadable", e)
            null
        }
    }

    /**
     * Mints a fresh passphrase, stores it wrapped, and returns it.
     *
     * **Written with `commit`, not `apply`.** The passphrase has to be on disk *before* anything
     * is encrypted with it: `apply` returns immediately and flushes later, so a process death in
     * between would leave a database encrypted under a passphrase nobody remembers — the very
     * loss this class exists to avoid, arriving through the storage API rather than the Keystore.
     * The cost is one blocking write of about a hundred bytes, once per install.
     */
    fun mint(): String {
        val material = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(material)
        val passphrase = material.joinToString("") { byte -> "%02x".format(byte) }
        preferences.edit()
            .putString(KEY_WRAPPED_PASSPHRASE, encryptionManager.encrypt(passphrase))
            .commit()
        return passphrase
    }

    companion object {
        private const val TAG = "DatabaseKey"

        /**
         * A file of its own rather than a key inside `encrypted_prefs`.
         *
         * That file is cleared wholesale when its keyset stops matching its master key — see
         * `EncryptedPreferences` — and this value must not be collateral damage of a recovery
         * aimed at OAuth tokens.
         */
        private const val STORE_NAME = "database_key"

        private const val KEY_WRAPPED_PASSPHRASE = "wrapped_passphrase"

        /** 256 bits, which SQLCipher then runs through its own key derivation. */
        private const val PASSPHRASE_BYTES = 32
    }
}
