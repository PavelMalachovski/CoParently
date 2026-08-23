package com.coparently.app.domain.guests

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The choices a parent is offered, and the one choice they are not. */
class GuestAccessDurationTest {

    private val now: Instant = Instant.parse("2026-08-23T09:30:00Z")

    @Test
    fun `every choice ends, and ends in the future`() {
        // The property that matters. If any entry here ever produced a non-positive or
        // already-past expiry, GuestGrantPolicy would read the grant it created as expired
        // and the parent would be told they had let somebody in who could not get in.
        GuestAccessDuration.entries.forEach { choice ->
            val expiry = choice.expiryFrom(now)
            assertTrue(expiry > now.toEpochMilli(), "${choice.name} does not end in the future")
            assertTrue(
                GuestGrantPolicy.isActive(grant(expiry), at = now),
                "${choice.name} produces a grant that is already inactive"
            )
        }
    }

    @Test
    fun `the default is the month GuestGrantPolicy documents`() {
        assertEquals(GuestAccessDuration.MONTH, GuestAccessDuration.DEFAULT)
        assertEquals(
            GuestGrantPolicy.defaultExpiry(from = now),
            GuestAccessDuration.DEFAULT.expiryFrom(now)
        )
    }

    @Test
    fun `the choices are offered shortest first`() {
        val lengths = GuestAccessDuration.entries.map { it.duration }

        assertEquals(lengths.sorted(), lengths, "the list must read shortest to longest")
    }

    private fun grant(expiresAtMillis: Long) = GuestGrant(
        uid = "gran-uid",
        name = "Nina",
        grantedBy = "alice-uid",
        grantedAtMillis = now.toEpochMilli(),
        expiresAtMillis = expiresAtMillis
    )
}
