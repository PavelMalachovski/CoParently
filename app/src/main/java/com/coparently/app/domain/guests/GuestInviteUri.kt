package com.coparently.app.domain.guests

import com.coparently.app.domain.pairing.PairingUri

/**
 * The `coplanly://guest?code=…` link a parent sends somebody they are letting in.
 *
 * A separate host from `coplanly://pair`, not a query parameter on it, and that is the whole
 * point: the link says which kind of invitation it carries, so the app knows which callable
 * to redeem it with before it asks the server anything. The two codes look identical — six
 * characters from the same generator — so without this the app would have to guess, and the
 * cost of guessing wrong in one direction is a guest who becomes a co-parent.
 *
 * The code itself is extracted by [PairingUri.extractCode], which looks for `CODE=` in
 * arbitrary text and validates the format. There is nothing guest-specific about that, and a
 * second copy of it is a second place for the two to drift apart.
 */
object GuestInviteUri {

    /** Host segment of a guest link; [PairingUri.SCHEME] is shared. */
    const val HOST = "guest"

    /** Builds the shareable link for [code]. */
    fun build(code: String): String = "${PairingUri.SCHEME}://$HOST?code=$code"

    /**
     * Whether [scheme] and [host] — as read off an incoming `Intent`'s `Uri` — identify a
     * guest link. Takes plain strings so this has no Android dependency, the same way
     * [PairingUri.isPairingUri] does.
     */
    fun isGuestUri(scheme: String?, host: String?): Boolean =
        scheme == PairingUri.SCHEME && host == HOST

    /**
     * Extracts a valid invite code from [input] — a bare code, a full guest link, or a pasted
     * message containing one.
     *
     * Deliberately identical to [PairingUri.extractCode] and delegating to it. A code alone
     * does not say which kind of invitation it belongs to; only the host does, and only when
     * the guest was sent a link rather than six characters in a text message.
     */
    fun extractCode(input: String): String? = PairingUri.extractCode(input)
}
