package com.coparently.app.domain.chat

/**
 * The `coplanly://chat` link a chat-message push notification opens.
 *
 * Mirrors `PairingUri`: a chat notification carries no conversation id in the link itself
 * (see `CoPlanlyMessagingService`) — the same "link present, no extra data" shape the
 * pairing deep link already uses for its own push notifications, which land on the pairing
 * screen with no prefilled code. This one lands on the Chat tab, since the underlying
 * conversation between two co-parents already has a deterministic id ([ConversationKey])
 * the client resolves for itself rather than needing it carried through the link.
 */
object ChatUri {

    /** Custom scheme; the app owns no domain, so App Links are not an option. */
    const val SCHEME = "coplanly"

    /** Host segment of the chat link. */
    const val HOST = "chat"

    /** The link itself, as opened by a chat-message push notification. */
    fun build(): String = "$SCHEME://$HOST"

    /**
     * Whether [scheme] and [host] (as read off an incoming `Intent`'s `Uri`, e.g.
     * `intent.data?.scheme` / `intent.data?.host`) identify a `coplanly://chat` link. Takes
     * plain strings rather than `android.net.Uri` so this check has no Android dependency and
     * can be unit-tested directly, matching
     * [com.coparently.app.domain.pairing.PairingUri.isPairingUri].
     */
    fun isChatUri(scheme: String?, host: String?): Boolean = scheme == SCHEME && host == HOST
}
