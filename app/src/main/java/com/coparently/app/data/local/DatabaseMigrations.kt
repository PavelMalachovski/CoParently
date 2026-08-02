package com.coparently.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
     * List of all migrations in order.
     */
    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12
    )
}
