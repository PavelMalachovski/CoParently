package com.coparently.app.domain.friends

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a calendar-friend grant still admits a read.
 *
 * The same three-way agreement `GuestGrantPolicy` documents: this, the `events` read rule's
 * expiry comparison, and any future sweep must answer identically, or a grant is live for one and
 * gone for another. The strict comparison and the fail-closed default are the whole content.
 */
class CalendarFriendPolicyTest {

    private val now = 1_000_000L

    private fun grant(expiresAtMillis: Long) = CalendarFriendGrant(
        friendUid = "friend",
        name = "Babushka",
        familyParents = listOf("mom", "dad"),
        grantedBy = "mom",
        grantedAtMillis = 1L,
        expiresAtMillis = expiresAtMillis
    )

    @Test
    fun `a grant ending in the future is live`() {
        assertTrue(CalendarFriendPolicy.isActive(grant(now + 1), now))
    }

    @Test
    fun `a grant ending exactly now is already over`() {
        // Strict, matching the rule's `request.time < expiresAtMillis`. If this rounded the
        // other way a grant would be live here and refused by the server.
        assertFalse(CalendarFriendPolicy.isActive(grant(now), now))
    }

    @Test
    fun `a grant that ended is not live`() {
        assertFalse(CalendarFriendPolicy.isActive(grant(now - 1), now))
    }

    @Test
    fun `no grant is not live`() {
        assertFalse(CalendarFriendPolicy.isActive(null, now))
    }

    @Test
    fun `a grant with no end fails closed rather than lasting forever`() {
        // The one default this feature must never have. A zero reaches here from an older
        // client or a partial write, and is treated as expired, not as unlimited.
        assertFalse(CalendarFriendPolicy.isActive(grant(0), now))
        assertFalse(CalendarFriendPolicy.isActive(grant(-1), now))
    }

    @Test
    fun `active keeps only the live grants`() {
        val live = grant(now + 1)
        val over = grant(now - 1)
        assertEquals(listOf(live), CalendarFriendPolicy.active(listOf(live, over), now))
    }
}
