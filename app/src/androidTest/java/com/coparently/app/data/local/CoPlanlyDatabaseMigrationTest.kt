package com.coparently.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [DatabaseMigrations.MIGRATION_11_12] against a real SQLite database and validates the
 * result against the exported schema, using [MigrationTestHelper].
 *
 * `MIGRATION_11_12` is the first migration in this project that rebuilds a table (drop +
 * recreate) rather than `ALTER TABLE ... ADD COLUMN` — a table rebuild can lose or misalign
 * rows in a way an additive migration cannot. Before this test, its only validation was a
 * single cold launch on the project owner's phone over his real messages: a check that, had it
 * failed, would have failed on his data. `runMigrationsAndValidate`'s `validateDroppedTables`
 * flag validates the rebuilt `conversations` table against `12.json` byte-for-byte — the same
 * column-name/affinity/notNull/primary-key comparison Room itself runs at app startup — so a
 * mismatch is caught here, on a throwaway test database, instead of there.
 */
@RunWith(AndroidJUnit4::class)
class CoPlanlyDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CoPlanlyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * A conversation row written against the v11 schema — including a non-default
     * `unreadCount`, so the migration dropping that column is unambiguous rather than
     * accidentally passing because the value already happened to be zero — must survive the
     * 11-to-12 migration with every carried-over column intact, `unreadCount` gone, and the
     * four new columns at their documented defaults.
     */
    @Test
    fun migrate11To12_preservesConversationRowAndDropsUnreadCount() {
        helper.createDatabase(TEST_DB, VERSION_11).apply {
            execSQL(
                """
                INSERT INTO conversations
                    (id, participantsJson, title, lastMessageId, unreadCount, createdAt, syncedToFirestore)
                VALUES
                    ('conv-1', '["uidA","uidB"]', 'Co-parent', 'msg-1', 7, '2026-08-01T10:00:00', 1)
                """.trimIndent()
            )
            // MigrationTestHelper re-opens the file by name for the next version; the
            // connection used to seed it must be closed first.
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_12,
            true,
            DatabaseMigrations.MIGRATION_11_12
        )

        val cursor = migrated.query("SELECT * FROM conversations")
        assertEquals("exactly one conversation row must survive the rebuild", 1, cursor.count)
        assertTrue(cursor.moveToFirst())

        // Carried-over columns, unchanged.
        assertEquals("conv-1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals(
            "[\"uidA\",\"uidB\"]",
            cursor.getString(cursor.getColumnIndexOrThrow("participantsJson"))
        )
        assertEquals("Co-parent", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("msg-1", cursor.getString(cursor.getColumnIndexOrThrow("lastMessageId")))
        assertEquals(
            "2026-08-01T10:00:00",
            cursor.getString(cursor.getColumnIndexOrThrow("createdAt"))
        )
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("syncedToFirestore")))

        // New columns, at their documented post-migration values for a pre-existing row.
        assertEquals("{}", cursor.getString(cursor.getColumnIndexOrThrow("lastReadAtJson")))
        assertEquals("{}", cursor.getString(cursor.getColumnIndexOrThrow("lastDeliveredAtJson")))
        val lastMessageAtMillisIndex = cursor.getColumnIndexOrThrow("lastMessageAtMillis")
        assertTrue(cursor.isNull(lastMessageAtMillisIndex))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("archived")))

        // The dropped column must be gone, not merely nulled out.
        assertEquals(-1, cursor.getColumnIndex("unreadCount"))

        cursor.close()
        migrated.close()
    }

    /**
     * A second, unrelated conversation must not be affected by — or merged with — the first;
     * the rebuild is a straight per-row carry, not something that could conflate rows.
     */
    @Test
    fun migrate11To12_preservesMultipleRowsIndependently() {
        helper.createDatabase(TEST_DB, VERSION_11).apply {
            execSQL(
                """
                INSERT INTO conversations
                    (id, participantsJson, title, lastMessageId, unreadCount, createdAt, syncedToFirestore)
                VALUES
                    ('conv-1', '["uidA","uidB"]', 'Co-parent', 'msg-1', 7, '2026-08-01T10:00:00', 1),
                    ('conv-2', '["uidA","uidC"]', 'Other thread', NULL, 0, '2026-07-15T09:30:00', 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_12,
            true,
            DatabaseMigrations.MIGRATION_11_12
        )

        val cursor = migrated.query("SELECT * FROM conversations ORDER BY id ASC")
        assertEquals(2, cursor.count)

        assertTrue(cursor.moveToFirst())
        assertEquals("conv-1", cursor.getString(cursor.getColumnIndexOrThrow("id")))

        assertTrue(cursor.moveToNext())
        assertEquals("conv-2", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertNull(cursor.getString(cursor.getColumnIndexOrThrow("lastMessageId")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("syncedToFirestore")))
        assertFalse(cursor.isNull(cursor.getColumnIndexOrThrow("lastReadAtJson")))

        cursor.close()
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "coplanly-migration-test.db"
        const val VERSION_11 = 11
        const val VERSION_12 = 12
    }
}
