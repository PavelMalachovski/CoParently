package com.coparently.app.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The arithmetic at the heart of `MIGRATION_12_13`, which turns every stored `messages`
 * wall-clock string into an instant.
 *
 * The rows being converted were written by *this* device, so this device's own zone is the
 * right one to read them in — and getting that wrong shifts the owner's real message history.
 * The row-carrying half of the migration is covered by the instrumented
 * `CoPlanlyDatabaseMigrationTest`; this covers the conversion itself, including the formats
 * `DateTimeFormatter.ISO_LOCAL_DATE_TIME` actually emits, which vary with how much sub-second
 * precision the value happens to carry.
 */
class DatabaseMigrationsWallClockTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun captureDefaultZone() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `a whole-second wall clock becomes the instant it names in this device's zone`() {
        val millis = inZone(ZoneOffset.ofHours(2)) {
            DatabaseMigrations.wallClockToEpochMillis("2026-08-01T12:00:00")
        }

        // 12:00 at UTC+2 is 10:00 UTC.
        assertEquals(1_785_578_400_000L, millis)
    }

    @Test
    fun `the same wall clock in a different zone is a different instant`() {
        val stored = "2026-08-01T12:00:00"

        val atUtc = inZone(ZoneOffset.UTC) { DatabaseMigrations.wallClockToEpochMillis(stored) }
        val atPlusTwo =
            inZone(ZoneOffset.ofHours(2)) { DatabaseMigrations.wallClockToEpochMillis(stored) }

        // Not a defect but the whole reason the migration exists: a naive wall clock only names
        // an instant once someone supplies a zone. The migration supplies the one that wrote it.
        assertEquals(2L * 60 * 60 * 1000, atUtc - atPlusTwo)
    }

    /**
     * `LocalDateTime.format(ISO_LOCAL_DATE_TIME)` omits the fraction entirely at whole seconds
     * and otherwise emits three, six or nine digits. All four shapes are in the owner's table.
     */
    @Test
    fun `every precision ISO_LOCAL_DATE_TIME emits is accepted`() {
        val stored = listOf(
            "2026-08-01T12:00:00",
            "2026-08-01T12:00:00.123",
            "2026-08-01T12:00:00.123456",
            "2026-08-01T12:00:00.123456789"
        )

        val millis = inZone(ZoneOffset.UTC) {
            stored.map { DatabaseMigrations.wallClockToEpochMillis(it) }
        }

        assertEquals(
            listOf(
                1_785_585_600_000L,
                1_785_585_600_123L,
                1_785_585_600_123L,
                1_785_585_600_123L
            ),
            millis
        )
    }

    @Test
    fun `a value that cannot be read lands at the epoch rather than dropping the message`() {
        assertEquals(0L, DatabaseMigrations.wallClockToEpochMillis("not-a-date"))
        assertEquals(0L, DatabaseMigrations.wallClockToEpochMillis(""))
        assertEquals(0L, DatabaseMigrations.wallClockToEpochMillis(null))
    }

    /** Runs [block] with [zone] as the JVM's default, restoring the previous one afterwards. */
    private fun <T> inZone(zone: ZoneId, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
