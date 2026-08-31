package com.coparently.app.data.local.security

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.coparently.app.data.crashlytics.CrashlyticsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens Room on an encrypted SQLite file, converting an existing plaintext one on the way (SEC-2).
 *
 * **What this buys.** `allowBackup="false"` and `data_extraction_rules.xml` already keep the
 * database off cloud backup and out of device-to-device transfer, so the residual exposure was
 * physical or root access to the device — where a child's medical profile, the whole chat history,
 * every expense and the `isPrivate` events that never leave the phone sat in a file any SQLite
 * viewer opens. SQLCipher encrypts the pages *and* the header with a key that only exists inside
 * the Android Keystore, so the file alone is worth nothing.
 *
 * It is not protection against a compromised running app, and it is not end-to-end encryption of
 * what syncs: what leaves the device for Firestore is governed by `firestore.rules`, unchanged.
 *
 * **Why the whole database rather than the medical fields.** The audit offered field-level
 * encryption of the medical profile as a smaller first step, and it is not a smaller step — it is
 * a broken one. `child_info` syncs, and this key is device-bound: a field encrypted here would
 * arrive at the co-parent's phone as ciphertext their Keystore cannot open. Making that work means
 * decrypting on the way out and re-encrypting on the way in, at every sync path, forever — more
 * code and more places to get it wrong than SQLCipher, in exchange for covering one table instead
 * of eleven. `SensitiveMedicalData`, the unused half-implementation of exactly that idea, is
 * deleted with this change rather than left as an invitation.
 *
 * **The migration is the risk, so it is arranged to be recoverable rather than fast.**
 * [SqlCipherMigration] decides what to do from the files on disk; this class only carries it out.
 * The one ordering that must never change: the plaintext database is deleted only after a
 * *verified* encrypted copy exists beside it under a different name.
 *
 * **Unverified on a device at the time of writing.** The project has no instrumented test job
 * (CQ-1) and the sessions that produced this have no Android SDK, so the pure decision layer is
 * unit-tested and the SQLCipher calls are not exercised at all. The app is not published, so no
 * install but the developer's own is at stake — but the first run on a phone with real data is an
 * acceptance step somebody has to perform, and it belongs in REL-7's list.
 */
@Singleton
class EncryptedDatabase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseKey: DatabaseKey,
    private val crashlyticsManager: CrashlyticsManager
) {

    /**
     * Prepares the database file and returns the factory Room should open it with.
     *
     * Returns **null** to mean "open it the ordinary, unencrypted way", which happens only when
     * the conversion failed and the plaintext database is still intact. That is a deliberate
     * degradation and the least bad of three: crashing makes the app unusable, wiping trades data
     * the user has for a property they did not have a moment ago, and carrying on leaves them
     * exactly where they were, with the failure reported and another attempt on the next launch.
     *
     * @param databaseName The Room database file name.
     */
    fun openHelperFactory(databaseName: String): SupportSQLiteOpenHelper.Factory? {
        val database = context.getDatabasePath(databaseName)
        val export = File(database.parentFile, databaseName + SqlCipherMigration.EXPORT_SUFFIX)
        return try {
            // `net.zetetic:sqlcipher-android` does not load its own native library, and nothing
            // in the package works until this has run.
            System.loadLibrary("sqlcipher")
            SupportOpenHelperFactory(prepare(database, export).toByteArray(Charsets.US_ASCII))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            fallBackTo(database, export, e)
        } catch (e: UnsatisfiedLinkError) {
            // The AAR carries no native library for this device's ABI. It is an `Error` rather
            // than an `Exception` and would otherwise pass straight through the clause above,
            // taking the process with it over something a plaintext database rescues completely.
            fallBackTo(database, export, IllegalStateException("SQLCipher is unavailable", e))
        }
    }

    /**
     * Resolves the passphrase, carries out whatever [SqlCipherMigration] asks for, and returns it.
     *
     * Everything that can fail is inside this one call so that the caller's `try` covers all of
     * it — minting a passphrase reaches the Keystore and can throw like any other step here.
     */
    private fun prepare(database: File, export: File): String {
        val recovered = databaseKey.recover()
        val step = SqlCipherMigration.next(
            databaseExists = database.exists(),
            databaseIsPlaintext = SqlCipherMigration.looksLikePlaintext(database),
            exportExists = export.exists(),
            passphraseRecovered = recovered != null
        )
        // A recovered passphrase is reused; its absence means nothing readable is left for the
        // old one to open, so a fresh one is minted and replaces it.
        val key = recovered ?: databaseKey.mint()
        carryOut(step, database, export, key)
        return key
    }

    /** Carries out one [SqlCipherMigration.Step]. */
    private fun carryOut(step: SqlCipherMigration.Step, database: File, export: File, key: String) {
        when (step) {
            SqlCipherMigration.Step.NONE -> Unit
            SqlCipherMigration.Step.ENCRYPT -> encrypt(database, export, key)
            SqlCipherMigration.Step.FINISH_SWAP -> swapIn(export, database)
            SqlCipherMigration.Step.DISCARD_LEFTOVER -> deleteWithSidecars(export)
            SqlCipherMigration.Step.DISCARD_UNREADABLE -> {
                Log.e(TAG, "The database cannot be decrypted with any key this device still has")
                crashlyticsManager.recordException(
                    IllegalStateException("Database passphrase lost; local data discarded")
                )
                deleteWithSidecars(database)
                deleteWithSidecars(export)
            }
        }
    }

    /**
     * Decides what to do after a failure: open the database unencrypted, or refuse to open it.
     *
     * Falling back is safe while the plaintext original is still there, which the ordering in
     * [encrypt] guarantees for every failure up to the moment it is deleted. Any export sitting
     * beside it is unverified by construction — reaching here means the attempt threw — so the
     * partial copy goes and the known-good original stays.
     *
     * The one state that must not fall back is an export with no original: there the encrypted
     * copy is the only remaining record of the family's data, and returning null would have Room
     * create an empty plaintext database beside it, which the *next* launch would then read as a
     * fresh install and sweep the export as stale. Failing loudly is worse to use and the only
     * thing that keeps the data recoverable.
     */
    private fun fallBackTo(
        database: File,
        export: File,
        cause: Exception
    ): SupportSQLiteOpenHelper.Factory? {
        Log.e(TAG, "Could not open the database encrypted", cause)
        crashlyticsManager.recordException(cause)
        if (database.exists() && SqlCipherMigration.looksLikePlaintext(database)) {
            deleteWithSidecars(export)
            return null
        }
        if (!database.exists() && export.exists()) throw cause
        return null
    }

    /**
     * Converts the plaintext database at [database] into an encrypted one, in four ordered steps.
     *
     * Any stale export is removed first: it belongs to an attempt that did not finish, and the
     * original it was made from is the file still sitting here.
     */
    private fun encrypt(database: File, export: File, key: String) {
        deleteWithSidecars(export)
        val version = exportEncrypted(database, export, key)
        verify(export, key, version)
        deleteWithSidecars(database)
        swapIn(export, database)
    }

    /**
     * Copies every table into a new encrypted file and returns the schema version it carried.
     *
     * SQLCipher's own `SQLiteDatabase` opens an unencrypted file when the passphrase is empty,
     * which is what makes `ATTACH ... KEY` available on it. `user_version` is set explicitly on
     * the target because that is where Room keeps its schema version: an export that lost it
     * would look to Room like a database at version 0 and trigger a migration chain that has
     * nothing to migrate.
     */
    private fun exportEncrypted(database: File, export: File, key: String): Int {
        val source = SQLiteDatabase.openOrCreateDatabase(database.absolutePath, "", NO_FACTORY)
        try {
            val version = source.version
            source.execSQL("ATTACH DATABASE '${quote(export.absolutePath)}' AS encrypted KEY '$key'")
            // `rawQuery` rather than `execSQL`: `sqlcipher_export` is a function whose work
            // happens while the statement is stepped, and only a query steps it.
            source.rawQuery("SELECT sqlcipher_export('encrypted')", NO_ARGS).use { it.moveToFirst() }
            source.execSQL("PRAGMA encrypted.user_version = $version")
            source.execSQL("DETACH DATABASE encrypted")
            return version
        } finally {
            source.close()
        }
    }

    /**
     * Opens the export with the passphrase Room will use and checks it is the database expected.
     *
     * This is what earns the right to delete the original. Opening it proves the passphrase
     * derives the same key; the schema version proves the `PRAGMA` above landed; and reading
     * `sqlite_master` proves pages actually decrypt, which opening alone does not — SQLCipher does
     * not touch the file until the first read.
     */
    private fun verify(export: File, key: String, expectedVersion: Int) {
        val copy = SQLiteDatabase.openOrCreateDatabase(export.absolutePath, key, NO_FACTORY)
        try {
            val tables = copy.rawQuery("SELECT count(*) FROM sqlite_master", NO_ARGS).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            if (copy.version != expectedVersion || tables == 0) {
                throw IOException(
                    "Encrypted copy is not the database it was made from " +
                        "(version ${copy.version}, expected $expectedVersion, $tables tables)"
                )
            }
        } finally {
            copy.close()
        }
    }

    /**
     * Renames the verified export over the database name Room opens.
     *
     * Reached two ways, and both times the export has been verified: straight after [verify], or
     * on a later launch that found it with no original beside it, which by the ordering in
     * [encrypt] can only mean a verified copy whose rename did not happen.
     */
    private fun swapIn(export: File, database: File) {
        // The connections were closed cleanly, so SQLite has already checkpointed and removed any
        // journal of its own; these deletes only catch what a kill left behind, and a stale
        // journal named after the *old* path would be read by nothing after the rename anyway.
        sidecarsOf(export).forEach { it.delete() }
        if (!export.renameTo(database)) {
            throw IOException("Could not move the encrypted database into place")
        }
    }

    /** Removes a database file and the journal files SQLite keeps beside it. */
    private fun deleteWithSidecars(file: File) {
        file.delete()
        sidecarsOf(file).forEach { it.delete() }
    }

    private fun sidecarsOf(file: File): List<File> =
        SIDECAR_SUFFIXES.map { File(file.parentFile, file.name + it) }

    /** Doubles single quotes so a path can go inside a SQL string literal. */
    private fun quote(value: String): String = value.replace("'", "''")

    companion object {
        private const val TAG = "EncryptedDatabase"

        /** Write-ahead log, shared memory and the rollback journal. */
        private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        /** Named so the `null` in the call sites below cannot be read as an omitted argument. */
        private val NO_FACTORY: SQLiteDatabase.CursorFactory? = null

        private val NO_ARGS = emptyArray<String>()
    }
}
