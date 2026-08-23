package com.coparently.app.data.sync

import com.coparently.app.domain.guests.GuestGrant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading and writing the guest grants on a child document.
 *
 * A grant is an access decision, and `firestore.rules` and the scheduled sweep make the same
 * decision without being able to call this. So every case here is about not being **more
 * lenient** than they are: a grant this accepted but they rejected would show a parent that
 * somebody has access when they do not, and a grant this defaulted into permanence would be the
 * failure that never ends.
 */
class ChildInfoGuestsTest {

    private val grant = GuestGrant(
        uid = "gran-uid",
        name = "Nina",
        grantedBy = "alice-uid",
        grantedAtMillis = 1_787_000_000_000L,
        expiresAtMillis = 1_789_592_000_000L
    )

    @Test
    fun `a document written before this field existed reads as no guests`() {
        assertEquals(emptyMap(), ChildInfoGuests.decode(null))
        assertEquals(emptyMap(), ChildInfoGuests.decode(emptyMap<String, Any?>()))
    }

    @Test
    fun `a grant round-trips through the wire format`() {
        val encoded = ChildInfoGuests.encode(mapOf(grant.uid to grant))

        assertEquals(mapOf(grant.uid to grant), ChildInfoGuests.decode(encoded))
    }

    @Test
    fun `the uid is the key and is not repeated inside the value`() {
        // A copy that can disagree with the key is a copy that eventually will.
        val encoded = ChildInfoGuests.encode(mapOf(grant.uid to grant))

        @Suppress("UNCHECKED_CAST")
        val fields = encoded.getValue(grant.uid) as Map<String, Any?>
        assertTrue("uid" !in fields, "the uid was written inside the value as well as the key")
    }

    @Test
    fun `a grant missing its name or its granter is dropped, not defaulted into existence`() {
        // A half-read grant is a person with access whose name the parent cannot see, and a row
        // of blanks is not an honest answer to "who did I let in".
        val nameless = mapOf("gran-uid" to mapOf("grantedBy" to "alice-uid", "expiresAtMillis" to 1L))
        val unattributed = mapOf("gran-uid" to mapOf("name" to "Nina", "expiresAtMillis" to 1L))

        assertEquals(emptyMap(), ChildInfoGuests.decode(nameless))
        assertEquals(emptyMap(), ChildInfoGuests.decode(unattributed))
    }

    @Test
    fun `a grant with no expiry reads as expired, never as permanent`() {
        // 0 is what GuestGrantPolicy and the Firestore rule both treat as already past. This is
        // the case where being lenient would hand out a permanent grant by accident.
        val undated = mapOf("gran-uid" to mapOf("name" to "Nina", "grantedBy" to "alice-uid"))

        assertEquals(0L, ChildInfoGuests.decode(undated).getValue("gran-uid").expiresAtMillis)
    }

    @Test
    fun `a time that came back as a double is still read`() {
        // Firestore hands back a Long for a number written by another client and a Double for
        // one that went through JSON. Reading only one of the two would expire live grants.
        val viaJson = mapOf(
            "gran-uid" to mapOf(
                "name" to "Nina",
                "grantedBy" to "alice-uid",
                "expiresAtMillis" to 1_789_592_000_000.0
            )
        )

        assertEquals(
            1_789_592_000_000L,
            ChildInfoGuests.decode(viaJson).getValue("gran-uid").expiresAtMillis
        )
    }

    @Test
    fun `a value of the wrong shape is dropped rather than thrown on`() {
        assertEquals(emptyMap(), ChildInfoGuests.decode("gran-uid"))
        assertEquals(emptyMap(), ChildInfoGuests.decode(listOf("gran-uid")))
        assertEquals(emptyMap(), ChildInfoGuests.decode(mapOf("gran-uid" to "Nina")))
    }
}
