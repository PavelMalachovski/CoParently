package com.coparently.app.domain.custody

import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ordering the two phones' writes to the shared schedule.
 *
 * This is the comparison that decides which parent's custody pattern survives — and the winner
 * is not merely kept, it is re-pushed over the loser. So the cases that matter are the two ways
 * of being wrong: judging the wrong side newer, and judging *anything* newer when the answer is
 * not knowable.
 */
class CustodyTimestampsTest {

    private val prague = ZoneId.of("Europe/Prague")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun `two parents in different zones order the same two writes the same way`() {
        // The bug this replaces. Prague writes at 09:00 local, Tokyo at 10:00 local an hour
        // earlier in real time — so Prague's is genuinely the later write. Compared as naive
        // local strings, "10:00" beats "09:00" and Tokyo's stale pattern would have been
        // re-pushed over Prague's.
        val tokyoWrite = ZonedDateTime.of(2026, 8, 23, 10, 0, 0, 0, tokyo).toInstant()
        val pragueWrite = ZonedDateTime.of(2026, 8, 23, 9, 0, 0, 0, prague).toInstant()

        assertTrue(pragueWrite.isAfter(tokyoWrite), "the fixture must model the trap")
        assertTrue(
            CustodyTimestamps.isNewer(pragueWrite.toEpochMilli(), tokyoWrite.toEpochMilli())
        )
        assertFalse(
            CustodyTimestamps.isNewer(tokyoWrite.toEpochMilli(), pragueWrite.toEpochMilli())
        )
    }

    @Test
    fun `an equal instant is not newer`() {
        // Firestore echoes every write straight back. If an echo read as newer, the mirror
        // would re-push on every round trip, forever.
        assertFalse(CustodyTimestamps.isNewer(1_787_000_000_000L, 1_787_000_000_000L))
    }

    @Test
    fun `an unknown timestamp on either side is never newer`() {
        // "Cannot tell" must mean "leave the document alone", never "mine wins": the caller
        // re-pushes the local copy over the remote one when this says yes.
        assertFalse(CustodyTimestamps.isNewer(0L, 1_787_000_000_000L))
        assertFalse(CustodyTimestamps.isNewer(1_787_000_000_000L, 0L))
        assertFalse(CustodyTimestamps.isNewer(-1L, 1_787_000_000_000L))
        assertFalse(CustodyTimestamps.isNewer(0L, 0L))
    }

    @Test
    fun `a millis field is read straight through`() {
        assertEquals(
            1_787_000_000_000L,
            CustodyTimestamps.fromWire(millis = 1_787_000_000_000L, legacyIso = null)
        )
    }

    @Test
    fun `a number that came back as a double is still read`() {
        // Firestore hands back a Long for a number written by another client and a Double for
        // one that went through JSON.
        assertEquals(
            1_787_000_000_000L,
            CustodyTimestamps.fromWire(millis = 1_787_000_000_000.0, legacyIso = null)
        )
    }

    @Test
    fun `a document from an older build is read from its ISO string`() {
        val iso = "2026-08-23T09:00:00"
        val expected = ZonedDateTime.of(2026, 8, 23, 9, 0, 0, 0, prague).toInstant()

        assertEquals(
            expected.toEpochMilli(),
            CustodyTimestamps.fromWire(millis = null, legacyIso = iso, zone = prague)
        )
    }

    @Test
    fun `the millis field wins over the ISO one when both are present`() {
        // Both are written on every current write, and the ISO is the one that can be wrong.
        val fromMillis = 1_787_000_000_000L

        assertEquals(
            fromMillis,
            CustodyTimestamps.fromWire(
                millis = fromMillis,
                legacyIso = "2001-01-01T00:00:00",
                zone = prague
            )
        )
    }

    @Test
    fun `anything unusable reads as unknown, not as ancient`() {
        // Zero is what isNewer treats as "cannot tell". Returning a very old instant instead
        // would make every malformed document lose every comparison, which is the same silent
        // overwrite in a different disguise.
        assertEquals(0L, CustodyTimestamps.fromWire(millis = null, legacyIso = null))
        assertEquals(0L, CustodyTimestamps.fromWire(millis = 0L, legacyIso = ""))
        assertEquals(0L, CustodyTimestamps.fromWire(millis = "yesterday", legacyIso = "yesterday"))
        assertEquals(0L, CustodyTimestamps.fromWire(millis = null, legacyIso = "2026-08-23"))
        assertEquals(0L, CustodyTimestamps.fromWire(millis = -5L, legacyIso = null))
    }

    @Test
    fun `the legacy string round-trips within one zone`() {
        // What an older co-parent's build will parse. Within one zone it is exact; across two
        // it is the guess the doc names, which is why the millis field exists beside it.
        val instant = Instant.parse("2026-08-23T07:00:00Z")

        val iso = CustodyTimestamps.toLegacyIso(instant.toEpochMilli(), prague)

        assertEquals("2026-08-23T09:00:00", iso)
        assertEquals(
            instant.toEpochMilli(),
            CustodyTimestamps.fromWire(millis = null, legacyIso = iso, zone = prague)
        )
    }
}
