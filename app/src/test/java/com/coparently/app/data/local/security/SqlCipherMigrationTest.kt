package com.coparently.app.data.local.security

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a half-finished database conversion left off (SEC-2).
 *
 * The encryption itself is SQLCipher's problem. This is the part that is ours: reading a directory
 * and deciding what a killed process was in the middle of. Every case below is a state a real
 * device can be in, and in four of them the wrong answer deletes the only copy of a family's
 * calendar — so the truth table is walked exhaustively rather than sampled.
 */
class SqlCipherMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun next(
        databaseExists: Boolean = true,
        databaseIsPlaintext: Boolean = false,
        exportExists: Boolean = false,
        passphraseRecovered: Boolean = true
    ) = SqlCipherMigration.next(databaseExists, databaseIsPlaintext, exportExists, passphraseRecovered)

    @Test
    fun `a fresh install has nothing to convert`() {
        assertEquals(
            SqlCipherMigration.Step.NONE,
            next(databaseExists = false, passphraseRecovered = false)
        )
    }

    @Test
    fun `a plaintext database is converted`() {
        assertEquals(SqlCipherMigration.Step.ENCRYPT, next(databaseIsPlaintext = true))
    }

    @Test
    fun `a plaintext database is converted even with no passphrase to recover`() {
        // Nothing about a readable database depends on the old key: a lost one is replaced before
        // the export starts. Answering DISCARD_UNREADABLE here would delete a file that opens.
        assertEquals(
            SqlCipherMigration.Step.ENCRYPT,
            next(databaseIsPlaintext = true, passphraseRecovered = false)
        )
    }

    @Test
    fun `an encrypted database with its passphrase is simply opened`() {
        assertEquals(SqlCipherMigration.Step.NONE, next())
    }

    @Test
    fun `an export beside a finished database is stale`() {
        // The conversion completed and a later attempt died early. The database is authoritative,
        // so the leftover is the copy to drop.
        assertEquals(SqlCipherMigration.Step.DISCARD_LEFTOVER, next(exportExists = true))
    }

    @Test
    fun `an export with no database is the database`() {
        // Killed between deleting the verified original and renaming its replacement in. The
        // export holds everything, and finishing the rename is the only non-destructive answer.
        assertEquals(
            SqlCipherMigration.Step.FINISH_SWAP,
            next(databaseExists = false, exportExists = true)
        )
    }

    @Test
    fun `an encrypted database whose passphrase is gone is discarded`() {
        assertEquals(
            SqlCipherMigration.Step.DISCARD_UNREADABLE,
            next(passphraseRecovered = false)
        )
    }

    @Test
    fun `an export whose passphrase is gone is discarded too`() {
        // The same loss one step earlier: finishing the swap would install a file nothing on this
        // device can decrypt, and Room would then fail to open on every launch instead of once.
        assertEquals(
            SqlCipherMigration.Step.DISCARD_UNREADABLE,
            next(databaseExists = false, exportExists = true, passphraseRecovered = false)
        )
    }

    @Test
    fun `nothing is ever deleted while it is the only readable copy`() {
        // The property the individual cases are instances of, asserted over all sixteen states.
        // A step that removes a file is only ever correct when something else still holds the
        // data, or when nothing can read it at all.
        forEachState { database, plaintext, export, passphrase ->
            val step = SqlCipherMigration.next(database, plaintext, export, passphrase)
            val onlyCopyIsTheExport = !database && export
            if (onlyCopyIsTheExport && passphrase) {
                assertEquals(
                    SqlCipherMigration.Step.FINISH_SWAP,
                    step,
                    "the export is the only copy in $database/$plaintext/$export/$passphrase"
                )
            }
            if (database && plaintext) {
                assertEquals(
                    SqlCipherMigration.Step.ENCRYPT,
                    step,
                    "a readable database must never be discarded"
                )
            }
        }
    }

    @Test
    fun `every state resolves to exactly one step`() {
        // Totality, stated as a test because the function is the only thing standing between a
        // half-written directory and Room opening the wrong file. A `when` that grew a null or a
        // thrown branch would fail here rather than on somebody's phone.
        var states = 0
        forEachState { database, plaintext, export, passphrase ->
            SqlCipherMigration.next(database, plaintext, export, passphrase)
            states++
        }
        assertEquals(16, states)
    }

    @Test
    fun `a real SQLite file is recognised as plaintext`() {
        val file = folder.newFile("plain.db")
        // Sixteen bytes: fifteen characters and the NUL that terminates them. Written out here
        // rather than reused from the production constant, so that changing the constant fails
        // this test instead of silently agreeing with itself.
        file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64))

        assertTrue(SqlCipherMigration.looksLikePlaintext(file))
    }

    @Test
    fun `an encrypted file is not`() {
        // SQLCipher encrypts the header along with the pages, so byte zero onward looks random.
        val file = folder.newFile("cipher.db")
        file.writeBytes(ByteArray(128) { (it * 31 + 7).toByte() })

        assertFalse(SqlCipherMigration.looksLikePlaintext(file))
    }

    @Test
    fun `a missing, empty or truncated file is not plaintext`() {
        assertFalse(SqlCipherMigration.looksLikePlaintext(File(folder.root, "absent.db")))
        assertFalse(SqlCipherMigration.looksLikePlaintext(folder.newFile("empty.db")))

        val truncated = folder.newFile("truncated.db")
        truncated.writeBytes("SQLite".toByteArray(Charsets.US_ASCII))
        assertFalse(SqlCipherMigration.looksLikePlaintext(truncated))
    }

    private fun forEachState(block: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
        val bits = listOf(false, true)
        bits.forEach { database ->
            bits.forEach { plaintext ->
                bits.forEach { export ->
                    bits.forEach { passphrase -> block(database, plaintext, export, passphrase) }
                }
            }
        }
    }
}
