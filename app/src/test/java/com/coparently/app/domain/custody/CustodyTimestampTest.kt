package com.coparently.app.domain.custody

import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * How the pair's custody document says when it was last written.
 *
 * The value is not decoration: `CustodyModelRepository.isNewer` compares it and **re-pushes the
 * side it judges newer over the other**, so a disagreement about what it means is a schedule
 * being overwritten by the wrong phone. That was SEC-4, and these are the properties that close
 * it — most importantly the one the old wall clock did not have: two devices in different zones
 * must read the same instant out of the same document.
 */
class CustodyTimestampTest {

    @Test
    fun `an instant survives a round trip through the document`() {
        val millis = CustodyTimestamp.fromWire("2026-08-04T18:30:00")
        assertEquals("2026-08-04T18:30", CustodyTimestamp.toWire(millis))
    }

    @Test
    fun `two devices in different zones read the same instant out of one document`() {
        // The whole of SEC-4 in one assertion. The stored text carries no offset, so before this
        // it was read as each device's own wall clock and the two disagreed by exactly the gap
        // between their zones — which is how the wrong parent's schedule could win.
        val document = "2026-08-04T18:30:00"

        val inPrague = inZone("Europe/Prague") { CustodyTimestamp.fromWire(document) }
        val inChicago = inZone("America/Chicago") { CustodyTimestamp.fromWire(document) }

        assertEquals(inPrague, inChicago)
    }

    @Test
    fun `a document written in one zone is written identically in another`() {
        val millis = 1_754_332_200_000L

        val inPrague = inZone("Europe/Prague") { CustodyTimestamp.toWire(millis) }
        val inChicago = inZone("America/Chicago") { CustodyTimestamp.toWire(millis) }

        assertEquals(inPrague, inChicago)
    }

    @Test
    fun `later text is a later instant`() {
        assertEquals(
            true,
            CustodyTimestamp.fromWire("2026-08-04T18:31:00") >
                CustodyTimestamp.fromWire("2026-08-04T18:30:00")
        )
    }

    @Test
    fun `an undated document loses every comparison`() {
        // Absent, blank, or unreadable all land on the epoch — never on "now", which would make
        // an unreadable document win and be re-pushed over a readable one.
        assertEquals(CustodyTimestamp.UNDATED, CustodyTimestamp.fromWire(null))
        assertEquals(CustodyTimestamp.UNDATED, CustodyTimestamp.fromWire(""))
        assertEquals(CustodyTimestamp.UNDATED, CustodyTimestamp.fromWire("   "))
        assertEquals(CustodyTimestamp.UNDATED, CustodyTimestamp.fromWire("not a date"))
        assertEquals(CustodyTimestamp.UNDATED, CustodyTimestamp.fromWire("2026-08-04T18:30:00Z"))

        assertEquals(0L, CustodyTimestamp.UNDATED)
    }

    @Test
    fun `sub-second precision survives, because two writes can share a second`() {
        val millis = CustodyTimestamp.fromWire("2026-08-04T18:30:00.250")
        assertNotEquals(CustodyTimestamp.fromWire("2026-08-04T18:30:00"), millis)
        assertEquals("2026-08-04T18:30:00.250", CustodyTimestamp.toWire(millis))
    }

    /** Runs [block] with the JVM default zone set to [zone], restoring it afterwards. */
    private fun <T> inZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
        return try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
