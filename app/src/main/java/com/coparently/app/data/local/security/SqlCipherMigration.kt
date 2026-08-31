package com.coparently.app.data.local.security

import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * What has to happen to the database file before Room may open it (SEC-2).
 *
 * The interesting half of turning a plaintext Room database into an encrypted one is not the
 * encryption — SQLCipher does that — but deciding, from a directory on disk, **where a previous
 * attempt got to**. The app can be killed at any point: mid-export, between the verification and
 * the swap, between deleting the old file and renaming the new one. Every one of those states has
 * to resolve into an action that cannot lose a family's calendar.
 *
 * So the decision lives here as a total function of four booleans, with no Android in it, and
 * `SqlCipherMigrationTest` walks all sixteen combinations. The part that touches SQLCipher and the
 * file system is a thin executor in [EncryptedDatabase] that does what this says.
 *
 * **The invariant the whole thing rests on:** the plaintext database is deleted only after a
 * verified encrypted copy exists beside it under a different name. Nothing here may reorder that.
 */
object SqlCipherMigration {

    /**
     * The suffix of the half-built encrypted copy.
     *
     * A separate name rather than an in-place rewrite is what makes a crash recoverable: as long
     * as the two files have different names, the file system itself records which step was
     * reached, and no state exists in which both copies are gone.
     */
    const val EXPORT_SUFFIX: String = ".migrating"

    /** `SQLite format 3` plus a NUL — the sixteen bytes every unencrypted SQLite file starts with. */
    private val SQLITE_MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** What [EncryptedDatabase] must do before the database is opened. */
    enum class Step {
        /** Open it. Either it is already encrypted, or there is nothing on disk yet. */
        NONE,

        /** A plaintext database is present: export it through SQLCipher and swap the copy in. */
        ENCRYPT,

        /**
         * The export was verified and the plaintext original already deleted, but the rename did
         * not happen. Finish it — the export *is* the database now.
         */
        FINISH_SWAP,

        /**
         * An encrypted database is in place and an export file is left over from an attempt that
         * did not finish. The leftover is stale by definition and is deleted.
         */
        DISCARD_LEFTOVER,

        /**
         * There is an encrypted database and no passphrase that opens it.
         *
         * Nothing can read it again — the Keystore key that wrapped the passphrase is gone, which
         * happens on Keystore corruption and on some OEM lock-screen changes. The file is removed
         * so the app starts on an empty encrypted database and re-downloads what Firestore holds.
         * What that costs is real and is stated rather than hidden: private events, which never
         * sync by design, and anything still waiting in the outbox.
         */
        DISCARD_UNREADABLE
    }

    /**
     * The step to take, given what is on disk and whether the passphrase survived.
     *
     * @param databaseExists Whether the Room database file is present at all.
     * @param databaseIsPlaintext Whether that file is an unencrypted SQLite file — see
     *   [looksLikePlaintext]. Meaningless when [databaseExists] is false.
     * @param exportExists Whether a `.migrating` copy is present beside it.
     * @param passphraseRecovered Whether a stored passphrase was found *and* unwrapped. A fresh
     *   install has none, which is not a failure — nothing encrypted exists yet either.
     */
    @Suppress("ReturnCount")
    fun next(
        databaseExists: Boolean,
        databaseIsPlaintext: Boolean,
        exportExists: Boolean,
        passphraseRecovered: Boolean
    ): Step {
        if (!databaseExists) {
            // No database and no export is a fresh install. An export on its own can only be a
            // crash between deleting the verified original and renaming its replacement in, so
            // it holds the family's data and must be finished, not swept.
            if (!exportExists) return Step.NONE
            return if (passphraseRecovered) Step.FINISH_SWAP else Step.DISCARD_UNREADABLE
        }
        // A plaintext database is readable whatever happened to the passphrase, so this branch
        // never depends on it: a lost one is simply replaced before the export starts.
        if (databaseIsPlaintext) return Step.ENCRYPT
        if (!passphraseRecovered) return Step.DISCARD_UNREADABLE
        return if (exportExists) Step.DISCARD_LEFTOVER else Step.NONE
    }

    /**
     * Whether [file] is an unencrypted SQLite database.
     *
     * Read from the file's own first bytes rather than from a "we have migrated" flag in
     * preferences, and deliberately: a flag can be cleared, restored, or written out of order
     * with the thing it describes, and every one of those turns into opening a database the wrong
     * way. A header cannot disagree with the file it is in. SQLCipher encrypts the header too, so
     * an encrypted database starts with what looks like random bytes.
     *
     * A missing, empty or truncated file answers false — there is no plaintext data to preserve
     * in any of those cases, which is the only thing this answer is used to protect.
     */
    fun looksLikePlaintext(file: File): Boolean {
        val header = ByteArray(SQLITE_MAGIC.size)
        return try {
            // `readFully` rather than a single `read`: a short read is indistinguishable from a
            // short file otherwise, and it throws `EOFException` — an `IOException` — for both,
            // which is the answer this function wants for either.
            file.inputStream().use { stream -> DataInputStream(stream).readFully(header) }
            header.contentEquals(SQLITE_MAGIC)
        } catch (e: IOException) {
            // Unreadable is not plaintext. Treating it as plaintext would send it down the export
            // path, which would fail one step later with less to say about why.
            false
        }
    }
}
