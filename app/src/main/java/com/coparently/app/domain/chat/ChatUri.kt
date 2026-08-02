package com.coparently.app.domain.chat

/**
 * The `coplanly://chat` link a chat-message push notification opens.
 *
 * Mirrors `PairingUri`'s real shape: `PairingUri.build(code)` carries an optional `?code=`
 * query parameter, and the pairing *push* only omits it because no code is meaningful for a
 * `pairing_accepted`/`pairing_removed` event. A chat-message push, by contrast, always knows
 * which conversation the message belongs to (`notifyOfChatMessage` in `functions/index.js`
 * puts it in the queued notification's `data`), so carrying it through here — rather than
 * leaving the client to land one screen short of the thread — is the consistent move, not a
 * parallel mechanism.
 *
 * An absent or blank id still resolves to the Chat tab's conversation list rather than
 * failing: a manual test push, an older payload, or a link typed by hand may carry none.
 */
object ChatUri {

    /** Custom scheme; the app owns no domain, so App Links are not an option. */
    const val SCHEME = "coplanly"

    /** Host segment of the chat link. */
    const val HOST = "chat"

    /** Builds the link, optionally pointing at [conversationId]; the Chat tab when absent. */
    fun build(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) {
            "$SCHEME://$HOST"
        } else {
            "$SCHEME://$HOST?$CONVERSATION_ID_PARAM=$conversationId"
        }

    /**
     * Whether [scheme] and [host] (as read off an incoming `Intent`'s `Uri`, e.g.
     * `intent.data?.scheme` / `intent.data?.host`) identify a `coplanly://chat` link. Takes
     * plain strings rather than `android.net.Uri` so this check has no Android dependency and
     * can be unit-tested directly, matching
     * [com.coparently.app.domain.pairing.PairingUri.isPairingUri].
     */
    fun isChatUri(scheme: String?, host: String?): Boolean = scheme == SCHEME && host == HOST

    /**
     * Extracts the conversation id from [input], a full `coplanly://chat?conversationId=…`
     * link (or any string containing that query parameter). Returns null when the parameter
     * is absent or blank — the caller (see [com.coparently.app.presentation.MainActivity])
     * must treat that as "open the list", not as an error, since an id-less chat link is a
     * legitimate, expected shape (see the class doc).
     *
     * Takes a plain string rather than `android.net.Uri`, matching [isChatUri] and
     * [com.coparently.app.domain.pairing.PairingUri.extractCode], so this stays unit-testable
     * with no Android dependency.
     */
    fun extractConversationId(input: String): String? =
        CONVERSATION_ID_PATTERN.find(input)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private const val CONVERSATION_ID_PARAM = "conversationId"

    private val CONVERSATION_ID_PATTERN = Regex("$CONVERSATION_ID_PARAM=([^&]+)")
}
