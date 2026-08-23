package com.coparently.app.data.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who may read a child-info document.
 *
 * Until this existed, `SyncService.syncChildInfo` published only the creator and the last
 * modifier, and said so in a comment: the co-parent was never added, so a paired parent could not
 * see child information the other had entered. That was a feature never built rather than a bug,
 * and this is the policy it needed.
 *
 * The property that matters most is the last one. The audience is derived from live state, so the
 * moment `partnerId` is null — which is what an unpair leaves behind — an ex-partner is simply
 * absent from the next upload. There is no stored list they could linger in.
 */
class ChildInfoAudienceTest {

    private val me = "uid-me"
    private val partner = "uid-partner"

    @Test
    fun `a paired parent publishes to both of them`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = partner)

        assertEquals(setOf(me, partner), audience.toSet())
    }

    @Test
    fun `the creator is kept even when someone else uploads the row`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = "uid-other", partnerId = partner)

        assertTrue(audience.contains("uid-other"), "the creator must not lose their own document")
        assertTrue(audience.contains(me))
        assertTrue(audience.contains(partner))
    }

    @Test
    fun `an unpaired parent publishes only to themselves`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = null)

        assertEquals(listOf(me), audience)
    }

    @Test
    fun `an ex-partner cannot come back through a stale value`() {
        // partnerId null is exactly what an unpair leaves. Nothing else feeds this function,
        // so there is no path by which the ex-partner reappears.
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = null)

        assertFalse(audience.contains(partner))
    }

    @Test
    fun `blank and duplicate uids are dropped`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = "")

        assertEquals(listOf(me), audience)
    }

    @Test
    fun `a never-synced row with no creator still reaches both parents`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = null, partnerId = partner)

        assertEquals(setOf(me, partner), audience.toSet())
    }
}
