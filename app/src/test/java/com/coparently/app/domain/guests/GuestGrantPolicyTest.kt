package com.coparently.app.domain.guests

import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a guest may still read a child's record.
 *
 * Every case here decides access to a child's medical record, so the rule throughout is **fail
 * closed**: anything this cannot make sense of is inactive. An expiry that silently reads as
 * "never" is the failure that leaves a grandparent's weekend access in place for good.
 */
class GuestGrantPolicyTest {

    private val expiry: Instant = Instant.parse("2026-09-01T12:00:00Z")

    @Test
    fun `a grant is active before it expires`() {
        assertTrue(GuestGrantPolicy.isActive(grant(), at = expiry.minusSeconds(1)))
    }

    @Test
    fun `a grant is inactive after it expires`() {
        assertFalse(GuestGrantPolicy.isActive(grant(), at = expiry.plusSeconds(1)))
    }

    @Test
    fun `a grant is inactive exactly at its expiry`() {
        // The boundary belongs to the closed side. "Expires at noon" reads as "not after noon",
        // and an access token is the wrong place to round in the holder's favour. The rule and
        // the sweep implement the same strict comparison, so all three agree on this instant.
        assertFalse(GuestGrantPolicy.isActive(grant(), at = expiry))
    }

    @Test
    fun `an expiry that is not a time at all is inactive, never active`() {
        // A field that was absent, defaulted, or written by something that did not know it had
        // to fill it in. Reading zero as "no expiry" would turn a weekend into forever.
        assertFalse(GuestGrantPolicy.isActive(grant(expiresAtMillis = 0L), at = expiry))
        assertFalse(GuestGrantPolicy.isActive(grant(expiresAtMillis = -1L), at = expiry))
    }

    @Test
    fun `two readers in different zones agree about the same grant`() {
        // The trap CLAUDE.md records against the custody document's naive local `lastModifiedAt`,
        // which can pick the wrong winner by exactly the offset between two parents. An expiry
        // decides who may read a child's medical record; it must not depend on where the reader
        // is standing. Millis names one instant and there is no zone to get wrong.
        val ends = ZonedDateTime.of(2026, 9, 1, 14, 0, 0, 0, ZoneId.of("Europe/Prague")).toInstant()
        val grant = grant(expiresAtMillis = ends.toEpochMilli())
        val inPrague = ZonedDateTime.of(2026, 9, 1, 13, 0, 0, 0, ZoneId.of("Europe/Prague"))
        val inTokyo = inPrague.withZoneSameInstant(ZoneId.of("Asia/Tokyo"))

        assertEquals(inPrague.toInstant(), inTokyo.toInstant())
        assertTrue(GuestGrantPolicy.isActive(grant, at = inPrague.toInstant()))
        assertTrue(GuestGrantPolicy.isActive(grant, at = inTokyo.toInstant()))

        assertFalse(GuestGrantPolicy.isActive(grant, at = inPrague.plusHours(2).toInstant()))
    }

    @Test
    fun `the default expiry is thirty days out`() {
        val granted = Instant.parse("2026-08-23T09:30:00Z")

        val expires = GuestGrantPolicy.defaultExpiry(from = granted)

        assertEquals(granted.plusSeconds(30L * 24 * 60 * 60).toEpochMilli(), expires)
    }

    @Test
    fun `a grant written by defaultExpiry is active now and inactive in thirty-one days`() {
        val granted = Instant.parse("2026-08-23T09:30:00Z")
        val grant = grant(expiresAtMillis = GuestGrantPolicy.defaultExpiry(from = granted))

        assertTrue(GuestGrantPolicy.isActive(grant, at = granted))
        assertFalse(GuestGrantPolicy.isActive(grant, at = granted.plusSeconds(31L * 24 * 60 * 60)))
    }

    @Test
    fun `the active grants of a record are the ones a reader may see`() {
        val active = grant(uid = "gran-uid")
        val expired = grant(
            uid = "old-uid",
            expiresAtMillis = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
        )

        val visible = GuestGrantPolicy.active(
            listOf(active, expired),
            at = expiry.minusSeconds(1)
        )

        assertEquals(listOf("gran-uid"), visible.map { it.uid })
    }

    private fun grant(
        uid: String = "gran-uid",
        expiresAtMillis: Long = Instant.parse("2026-09-01T12:00:00Z").toEpochMilli()
    ) = GuestGrant(
        uid = uid,
        name = "Nina",
        grantedBy = "alice-uid",
        grantedAtMillis = Instant.parse("2026-08-23T09:30:00Z").toEpochMilli(),
        expiresAtMillis = expiresAtMillis
    )
}
