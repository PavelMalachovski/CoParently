package com.coparently.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Database migrations for CoPlanly database.
 * Each migration handles schema changes between versions.
 */
object DatabaseMigrations {

    /**
     * Migration from version 5 to 6.
     * Adds indexes to events table for improved query performance.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create indexes for frequently queried columns
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_events_startDateTime ON events(startDateTime)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_events_parentOwner ON events(parentOwner)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_events_parentOwner_startDateTime ON events(parentOwner, startDateTime)"
            )
        }
    }

    /**
     * Migration from version 6 to 7.
     * Adds status field to messages table for message send status tracking.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Add status column to messages table with default value 'SENT' for existing messages
            // SQLite doesn't support adding NOT NULL columns directly, so we:
            // 1. Add nullable column with DEFAULT
            // 2. Update all NULL values to 'SENT'
            // 3. Since Room expects NOT NULL, we need to ensure all values are set
            database.execSQL(
                "ALTER TABLE messages ADD COLUMN status TEXT DEFAULT 'SENT'"
            )
            // Ensure all existing messages have status set
            database.execSQL(
                "UPDATE messages SET status = 'SENT' WHERE status IS NULL"
            )
        }
    }

    /**
     * Migration from version 7 to 8.
     * Creates custody_models table for advanced custody pattern configuration.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS custody_models (
                    id TEXT PRIMARY KEY NOT NULL,
                    modelType TEXT NOT NULL,
                    patternDays INTEGER NOT NULL,
                    momDaysPattern TEXT NOT NULL,
                    startDate TEXT NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    repeatYearly INTEGER NOT NULL DEFAULT 1,
                    createdAt TEXT NOT NULL,
                    lastModifiedAt TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Migration from version 8 to 9.
     * Adds MVP1 fields to events: private events, pickup confirmation,
     * reminder offset and recurrence end date.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE events ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL(
                "ALTER TABLE events ADD COLUMN recurrenceEndDate TEXT"
            )
            database.execSQL(
                "ALTER TABLE events ADD COLUMN pickupConfirmedBy TEXT"
            )
            database.execSQL(
                "ALTER TABLE events ADD COLUMN pickupConfirmedAt TEXT"
            )
            database.execSQL(
                "ALTER TABLE events ADD COLUMN reminderMinutes INTEGER"
            )
        }
    }

    /**
     * Migration from version 9 to 10.
     * Creates the change_requests table (MVP 2 — event change requests).
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS change_requests (
                    id TEXT PRIMARY KEY NOT NULL,
                    eventId TEXT NOT NULL,
                    eventTitle TEXT NOT NULL,
                    requestedBy TEXT NOT NULL,
                    requestedTo TEXT NOT NULL,
                    currentStartDateTime TEXT NOT NULL,
                    currentEndDateTime TEXT,
                    proposedStartDateTime TEXT NOT NULL,
                    proposedEndDateTime TEXT,
                    note TEXT,
                    status TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    respondedAt TEXT,
                    syncedToFirestore INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_change_requests_eventId ON change_requests(eventId)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_change_requests_status ON change_requests(status)"
            )
        }
    }

    /**
     * Migration from version 10 to 11.
     * Adds an optional attached-photo URL to events (MVP 2 — attach image to event).
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE events ADD COLUMN imageUrl TEXT"
            )
        }
    }

    /**
     * Migration from version 11 to 12.
     *
     * Chat read state moves onto the conversation: two `{uid: epochMillis}` maps stored as
     * JSON, plus an ordering timestamp and the archive flag the legacy-conversation merge
     * sets. `unreadCount` is dropped — it is derived from `lastReadAt` now, and the stored
     * column was never incremented by anything.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // `unreadCount` has to go — Room validates the live schema against the entity
            // and a leftover column fails that check. SQLite cannot drop a column here, so
            // the table is rebuilt. The four new columns are supplied as literals in the
            // INSERT rather than added with ALTER first: the rebuild is happening anyway,
            // and adding them twice would be pure ceremony.
            database.execSQL(
                """
                CREATE TABLE conversations_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    participantsJson TEXT NOT NULL,
                    title TEXT NOT NULL,
                    lastMessageId TEXT,
                    lastReadAtJson TEXT NOT NULL DEFAULT '{}',
                    lastDeliveredAtJson TEXT NOT NULL DEFAULT '{}',
                    lastMessageAtMillis INTEGER,
                    archived INTEGER NOT NULL DEFAULT 0,
                    createdAt TEXT NOT NULL,
                    syncedToFirestore INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO conversations_new
                    (id, participantsJson, title, lastMessageId, lastReadAtJson,
                     lastDeliveredAtJson, lastMessageAtMillis, archived, createdAt, syncedToFirestore)
                SELECT id, participantsJson, title, lastMessageId, '{}',
                       '{}', NULL, 0, createdAt, syncedToFirestore
                FROM conversations
                """.trimIndent()
            )
            database.execSQL("DROP TABLE conversations")
            database.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
        }
    }

    /**
     * Migration from version 12 to 13.
     *
     * `messages.timestamp` was a naive wall-clock string — the sending device's local time,
     * with no offset — which cannot be compared against the conversation's read/delivered
     * marks once the two parents are in different timezones. It becomes `sentAtMillis`, an
     * instant. SQLite cannot change a column's declared type in place, so the table is rebuilt
     * the same way [MIGRATION_11_12] rebuilt `conversations`.
     *
     * **The conversion is deliberately done in Kotlin, not in SQL.** SQLite could express it as
     * `strftime('%s', timestamp, 'utc')`, but that parser accepts only up to three fractional
     * second digits and yields `NULL` for anything else, while the stored values come from
     * `DateTimeFormatter.ISO_LOCAL_DATE_TIME`, which writes up to nine — every sub-second value
     * would silently become `NULL` in a `NOT NULL` column. `strftime` also truncates to whole
     * seconds. Reading each row back through `java.time` instead is the exact inverse of what
     * wrote it, and reuses the same "interpret in this device's own zone" rule the app applied
     * to these rows until now — which is the correct rule here, because it is the same device
     * that wrote them.
     *
     * The rows are copied in bulk first and their instants written afterwards, so a value that
     * cannot be read back can only cost that message its position in the thread, never the
     * message itself.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE messages_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    conversationId TEXT NOT NULL,
                    senderId TEXT NOT NULL,
                    senderName TEXT NOT NULL,
                    content TEXT NOT NULL,
                    sentAtMillis INTEGER NOT NULL,
                    messageType TEXT NOT NULL,
                    attachmentsJson TEXT NOT NULL,
                    isRead INTEGER NOT NULL,
                    replyToMessageId TEXT,
                    syncedToFirestore INTEGER NOT NULL,
                    status TEXT DEFAULT 'SENT'
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO messages_new
                    (id, conversationId, senderId, senderName, content, sentAtMillis,
                     messageType, attachmentsJson, isRead, replyToMessageId,
                     syncedToFirestore, status)
                SELECT id, conversationId, senderId, senderName, content, 0,
                       messageType, attachmentsJson, isRead, replyToMessageId,
                       syncedToFirestore, status
                FROM messages
                """.trimIndent()
            )

            // Read every wall clock out first, then write the instants back: iterating a cursor
            // over `messages` while writing to `messages_new` in the same transaction is safe
            // today, but nothing about this migration needs to depend on that.
            val instants = mutableListOf<Pair<String, Long>>()
            database.query("SELECT id, timestamp FROM messages").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    instants += id to wallClockToEpochMillis(cursor.getString(1))
                }
            }
            instants.forEach { (id, sentAtMillis) ->
                database.execSQL(
                    "UPDATE messages_new SET sentAtMillis = ? WHERE id = ?",
                    arrayOf<Any>(sentAtMillis, id)
                )
            }

            database.execSQL("DROP TABLE messages")
            database.execSQL("ALTER TABLE messages_new RENAME TO messages")
        }
    }

    /**
     * A schema-12 `messages.timestamp` as epoch millis, interpreted in this device's own zone.
     *
     * The stored value is a naive `LocalDateTime` written by `Converters.fromLocalDateTime` on
     * *this* device, so this device's current zone is the right — and only — one to read it in.
     *
     * A value that cannot be read at all falls back to the epoch rather than dropping the row
     * or inventing "now": the message stays in the thread, at the top of it, where it is
     * visible and can never masquerade as new. This is not expected to happen — every row was
     * written by `DateTimeFormatter.ISO_LOCAL_DATE_TIME` — and losing a message would be far
     * worse than misplacing one.
     */
    internal fun wallClockToEpochMillis(stored: String?): Long {
        if (stored == null) return UNREADABLE_SENT_AT_MILLIS
        return runCatching {
            LocalDateTime.parse(stored, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(UNREADABLE_SENT_AT_MILLIS)
    }

    /** Where a message whose stored wall clock cannot be parsed lands. */
    private const val UNREADABLE_SENT_AT_MILLIS = 0L

    /**
     * Adds the parent and child medical profiles, and removes a subsystem that never ran.
     *
     * Purely additive on the two live tables: `ALTER TABLE ... ADD COLUMN` with defaults, so no
     * table is rebuilt and no stored value is read or rewritten. That is the whole reason
     * `MedicalProfile` keeps `allergies` outside it — folding it into the JSON blob would have
     * meant moving `child_info.allergiesJson` into a new column, and SQLite cannot drop the old
     * one without recreating the table.
     *
     * The four `DROP TABLE`s remove `medical_records`, `allergies`, `grades` and `school_events`.
     * `MedicalRepositoryImpl` and `EducationRepositoryImpl` were never bound in `RepositoryModule`
     * and no ViewModel or use case ever referenced either interface, so these tables have only
     * ever been empty — nothing has been able to write to them. `IF EXISTS` covers an install
     * where a partially-applied earlier migration left one missing.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE child_info ADD COLUMN medicalProfileJson TEXT NOT NULL DEFAULT '{}'"
            )
            database.execSQL("ALTER TABLE users ADD COLUMN dateOfBirth TEXT")
            database.execSQL("ALTER TABLE users ADD COLUMN phone TEXT")
            database.execSQL(
                "ALTER TABLE users ADD COLUMN allergiesJson TEXT NOT NULL DEFAULT '[]'"
            )
            database.execSQL(
                "ALTER TABLE users ADD COLUMN medicalProfileJson TEXT NOT NULL DEFAULT '{}'"
            )

            database.execSQL("DROP TABLE IF EXISTS medical_records")
            database.execSQL("DROP TABLE IF EXISTS allergies")
            database.execSQL("DROP TABLE IF EXISTS grades")
            database.execSQL("DROP TABLE IF EXISTS school_events")
        }
    }

    /**
     * Records when a user finished first-run onboarding.
     *
     * A single nullable column, so the migration cannot lose anything it does not touch. Null on
     * every existing row is the correct starting state: `OnboardingState` treats an account that
     * already has a profile name and a child as complete regardless, so no existing user is
     * handed a questionnaire about data they already entered.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE users ADD COLUMN onboardingCompletedAt TEXT")
        }
    }

    /**
     * List of all migrations in order.
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15
    )
}
