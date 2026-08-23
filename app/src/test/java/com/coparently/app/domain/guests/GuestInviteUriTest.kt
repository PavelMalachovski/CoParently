package com.coparently.app.domain.guests

import com.coparently.app.domain.pairing.PairingUri
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guest link, and the one thing it exists to do: say which kind of invitation it carries.
 *
 * A guest code and a co-parent code are six characters from the same generator, so the host
 * is the only thing that tells them apart before the server is asked. Getting that wrong in
 * one direction makes a grandmother a co-parent.
 */
class GuestInviteUriTest {

    @Test
    fun `a guest link round-trips its code`() {
        val link = GuestInviteUri.build("4F7K2M")

        assertEquals("coplanly://guest?code=4F7K2M", link)
        assertEquals("4F7K2M", GuestInviteUri.extractCode(link))
    }

    @Test
    fun `a guest link is not a pairing link, and a pairing link is not a guest link`() {
        // The whole reason the host differs. If either of these ever passes the other's
        // check, one kind of code is being redeemed through the wrong callable.
        assertTrue(GuestInviteUri.isGuestUri("coplanly", "guest"))
        assertFalse(GuestInviteUri.isGuestUri("coplanly", "pair"))
        assertFalse(PairingUri.isPairingUri("coplanly", "guest"))
        assertTrue(PairingUri.isPairingUri("coplanly", "pair"))
    }

    @Test
    fun `another app's scheme is not a guest link`() {
        assertFalse(GuestInviteUri.isGuestUri("https", "guest"))
        assertFalse(GuestInviteUri.isGuestUri(null, null))
    }

    @Test
    fun `a bare code typed by hand is accepted`() {
        // A parent will read the six characters out over the phone as often as they send a
        // link. The host is then simply not available, and the screen the guest is standing
        // on is what decides.
        assertEquals("4F7K2M", GuestInviteUri.extractCode("4f7k2m"))
        assertEquals("4F7K2M", GuestInviteUri.extractCode("  4F7K2M  "))
    }

    @Test
    fun `text that carries no code yields none`() {
        assertNull(GuestInviteUri.extractCode("come and see the photos"))
        assertNull(GuestInviteUri.extractCode(""))
        assertNull(GuestInviteUri.extractCode("coplanly://guest"))
    }
}
