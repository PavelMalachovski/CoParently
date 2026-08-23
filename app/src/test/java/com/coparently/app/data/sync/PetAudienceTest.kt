package com.coparently.app.data.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who may read a pet document.
 *
 * Same policy as [ChildInfoAudienceTest] minus guests: the audience is derived from live
 * pairing state, so an unpair (which leaves `partnerId` null) drops the ex-partner from the
 * very next upload. There is no stored list they could linger in.
 */
class PetAudienceTest {

    private val me = "uid-me"
    private val partner = "uid-partner"

    @Test
    fun `a paired parent publishes to both of them`() {
        val audience = PetAudience.entitled(me, creatorUid = me, partnerId = partner)

        assertEquals(setOf(me, partner), audience.toSet())
    }

    @Test
    fun `the creator is kept even when someone else uploads the row`() {
        val audience = PetAudience.entitled(me, creatorUid = "uid-other", partnerId = partner)

        assertTrue(audience.contains("uid-other"), "the creator must not lose their own document")
        assertTrue(audience.contains(me))
        assertTrue(audience.contains(partner))
    }

    @Test
    fun `an unpaired parent publishes only to themselves`() {
        val audience = PetAudience.entitled(me, creatorUid = me, partnerId = null)

        assertEquals(listOf(me), audience)
    }

    @Test
    fun `an ex-partner cannot come back through a stale value`() {
        val audience = PetAudience.entitled(me, creatorUid = me, partnerId = null)

        assertFalse(audience.contains(partner))
    }

    @Test
    fun `blank and duplicate uids are dropped`() {
        val audience = PetAudience.entitled(me, creatorUid = me, partnerId = "")

        assertEquals(listOf(me), audience)
    }

    @Test
    fun `a never-synced row with no creator still reaches both parents`() {
        val audience = PetAudience.entitled(me, creatorUid = null, partnerId = partner)

        assertEquals(setOf(me, partner), audience.toSet())
    }
}
