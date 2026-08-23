package com.coparently.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Runs the table-rebuilding migrations against a real SQLite database and validates the result
 * against the exported schema, using [MigrationTestHelper].
 *
 * [DatabaseMigrations.MIGRATION_11_12] and [DatabaseMigrations.MIGRATION_12_13] are the only
 * migrations in this project that rebuild a table (drop + recreate) rather than
 * `ALTER TABLE ... ADD COLUMN` — a table rebuild can lose or misalign rows in a way an additive
 * migration cannot, and 12-to-13 additionally *rewrites* every message's send time. Before this
 * test, `MIGRATION_11_12`'s only validation was a single cold launch on the project owner's
 * phone over his real messages: a check that, had it failed, would have failed on his data.
 * `runMigrationsAndValidate`'s `validateDroppedTables` flag validates the rebuilt table against
 * the exported schema byte-for-byte — the same column-name/affinity/notNull/primary-key
 * comparison Room itself runs at app startup — so a mismatch is caught here, on a throwaway
 * test database, instead of there.
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

    private lateinit var originalZone: TimeZone

    /**
     * `MIGRATION_12_13` reads stored wall clocks in the device's own zone, so the expected
     * instants below are only literals if the zone is one. A fixed offset, not a named zone, so
     * no DST transition can blur what a given wall clock means — and a half-hour one no device
     * or emulator running this suite is plausibly set to, so a test that passes here cannot be
     * passing merely because the forced zone happened to match the machine's own.
     */
    @Before
    fun fixDefaultZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofHoursMinutes(5, 30)))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalZone)
    }

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

    /**
     * Every message must survive 12-to-13 with its send time converted — not defaulted, not
     * dropped — from the wall clock it was stored as to the instant that wall clock named on
     * this device.
     *
     * The three rows cover what `DateTimeFormatter.ISO_LOCAL_DATE_TIME` actually writes: no
     * fraction at whole seconds, and up to nine digits otherwise. A row per format matters
     * because a conversion that only understands one of them would fail on the others.
     */
    @Test
    fun migrate12To13_convertsEveryStoredWallClockToItsInstant() {
        helper.createDatabase(TEST_DB, VERSION_12).apply {
            execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, senderName, content, timestamp, messageType,
                     attachmentsJson, isRead, replyToMessageId, syncedToFirestore, status)
                VALUES
                    ('msg-1', 'uidA__uidB', 'uidA', 'Anna', 'See you at 5',
                     '2026-08-01T12:00:00', 'TEXT', '[]', 0, NULL, 1, 'SENT'),
                    ('msg-2', 'uidA__uidB', 'uidB', 'Bob', 'On my way',
                     '2026-08-01T12:00:00.123', 'TEXT', '["photo"]', 1, 'msg-1', 0, NULL),
                    ('msg-3', 'uidA__uidB', 'uidA', 'Anna', 'Thanks',
                     '2026-08-01T12:00:00.123456789', 'TEXT', '[]', 0, NULL, 1, 'SENDING')
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_13,
            true,
            DatabaseMigrations.MIGRATION_12_13
        )

        val cursor = migrated.query("SELECT * FROM messages ORDER BY id ASC")
        assertEquals("no message may be lost by the rebuild", 3, cursor.count)

        // 12:00 at UTC+05:30 is 06:30 UTC.
        assertTrue(cursor.moveToFirst())
        assertEquals("msg-1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals(
            NOON_AT_PLUS_FIVE_THIRTY_MILLIS,
            cursor.getLong(cursor.getColumnIndexOrThrow("sentAtMillis"))
        )
        // Every other column carries over untouched.
        assertEquals("uidA__uidB", cursor.getString(cursor.getColumnIndexOrThrow("conversationId")))
        assertEquals("uidA", cursor.getString(cursor.getColumnIndexOrThrow("senderId")))
        assertEquals("Anna", cursor.getString(cursor.getColumnIndexOrThrow("senderName")))
        assertEquals("See you at 5", cursor.getString(cursor.getColumnIndexOrThrow("content")))
        assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("messageType")))
        assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("attachmentsJson")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isRead")))
        assertNull(cursor.getString(cursor.getColumnIndexOrThrow("replyToMessageId")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("syncedToFirestore")))
        assertEquals("SENT", cursor.getString(cursor.getColumnIndexOrThrow("status")))

        // Sub-second precision is kept to the millisecond and truncated below it.
        assertTrue(cursor.moveToNext())
        assertEquals("msg-2", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals(
            NOON_AT_PLUS_FIVE_THIRTY_MILLIS + 123,
            cursor.getLong(cursor.getColumnIndexOrThrow("sentAtMillis"))
        )
        assertEquals("""["photo"]""", cursor.getString(cursor.getColumnIndexOrThrow("attachmentsJson")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("isRead")))
        assertEquals("msg-1", cursor.getString(cursor.getColumnIndexOrThrow("replyToMessageId")))
        assertNull("a null status must stay null", cursor.getString(cursor.getColumnIndexOrThrow("status")))

        assertTrue(cursor.moveToNext())
        assertEquals("msg-3", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals(
            NOON_AT_PLUS_FIVE_THIRTY_MILLIS + 123,
            cursor.getLong(cursor.getColumnIndexOrThrow("sentAtMillis"))
        )
        assertEquals("SENDING", cursor.getString(cursor.getColumnIndexOrThrow("status")))

        // The naive column is gone, not merely ignored.
        assertEquals(-1, cursor.getColumnIndex("timestamp"))

        cursor.close()
        migrated.close()
    }

    /**
     * An empty `messages` table is the common case for a fresh install that has never chatted,
     * and a rebuild driven by a per-row loop is exactly the shape that can trip over one.
     */
    @Test
    fun migrate12To13_handlesAnEmptyMessagesTable() {
        helper.createDatabase(TEST_DB, VERSION_12).close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_13,
            true,
            DatabaseMigrations.MIGRATION_12_13
        )

        val cursor = migrated.query("SELECT * FROM messages")
        assertEquals(0, cursor.count)

        cursor.close()
        migrated.close()
    }

    /**
     * A pre-existing `child_info` row must survive 13-to-14 with `allergiesJson` untouched — the
     * migration is purely additive and never reads or rewrites that column — and the new
     * `medicalProfileJson` column defaulted to `{}`.
     */
    @Test
    fun migration13To14_keepsChildInfoAndDefaultsTheNewColumns() {
        val db = helper.createDatabase(TEST_DB, 13)
        db.execSQL(
            """
            INSERT INTO child_info
                (id, childName, dateOfBirth, medicationsJson, activitiesJson, allergiesJson,
                 medicalNotes, emergencyContactsJson, schoolInfoJson, createdAt, updatedAt,
                 createdByFirebaseUid, lastModifiedBy, syncedToFirestore)
            VALUES ('c1', 'Anya', NULL, '[]', '[]', '["peanuts"]', NULL, '[]', NULL,
                    '2026-08-01T09:00:00', '2026-08-01T09:00:00', 'uid-1', 'uid-1', 1)
            """.trimIndent()
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 14, true, DatabaseMigrations.MIGRATION_13_14
        )

        migrated.query("SELECT childName, allergiesJson, medicalProfileJson FROM child_info").use {
            assertTrue(it.moveToFirst())
            assertEquals("Anya", it.getString(0))
            // The pre-existing allergy survives: MedicalProfile deliberately does not absorb it,
            // so this column is never read or rewritten by the migration.
            assertEquals("[\"peanuts\"]", it.getString(1))
            assertEquals("{}", it.getString(2))
        }
    }

    /**
     * `medical_records`, `allergies`, `grades` and `school_events` were never reachable — no
     * repository binding ever wrote to them — and 13-to-14 drops all four outright.
     */
    @Test
    fun migration13To14_dropsTheSubsystemThatNeverRan() {
        val db = helper.createDatabase(TEST_DB, 13)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 14, true, DatabaseMigrations.MIGRATION_13_14
        )

        for (table in listOf("medical_records", "allergies", "grades", "school_events")) {
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
            ).use {
                assertFalse("$table survived the migration", it.moveToFirst())
            }
        }
    }

    /**
     * 14-to-15 adds the first-run marker, and adds nothing else.
     *
     * Null on every existing row is the correct starting state rather than an oversight:
     * `OnboardingState` treats an account that already carries a name and a child as complete
     * by evidence, so a long-standing installation upgrading with a null marker is never handed
     * a questionnaire about data it already holds. An empty string would be a different value
     * with the same intent, which is why this asserts null specifically.
     */
    @Test
    fun migration14To15_addsTheOnboardingMarkerAsNull() {
        val db = helper.createDatabase(TEST_DB, VERSION_14)
        db.execSQL(
            """
            INSERT INTO users (id, email, name, role, colorCode, googleCalendarSyncEnabled,
                               allergiesJson, medicalProfileJson)
            VALUES ('u1', 'a@example.com', 'Olya', 'mom', '#FF4081', 0, '[]', '{}')
            """.trimIndent()
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, VERSION_15, true, DatabaseMigrations.MIGRATION_14_15
        )

        migrated.query("SELECT name, onboardingCompletedAt FROM users").use {
            assertTrue(it.moveToFirst())
            assertEquals("Olya", it.getString(0))
            assertTrue("the marker must start null, not empty", it.isNull(1))
        }
    }

    private companion object {
        const val TEST_DB = "coplanly-migration-test.db"
        const val VERSION_11 = 11
        const val VERSION_12 = 12
        const val VERSION_13 = 13
        const val VERSION_14 = 14
        const val VERSION_15 = 15

        /** 2026-08-01T12:00:00 at UTC+05:30, i.e. 06:30:00Z. */
        const val NOON_AT_PLUS_FIVE_THIRTY_MILLIS = 1_785_565_800_000L
    }
}
