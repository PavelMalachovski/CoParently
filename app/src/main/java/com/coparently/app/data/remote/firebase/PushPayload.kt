package com.coparently.app.data.remote.firebase

/**
 * What a queued push carries — the one definition both ends of it agree on (SEC-3).
 *
 * A notification used to arrive with its **text already written**: the sending device composed
 * `title` and `body` in English and `notification_queue` relayed them verbatim for the other
 * phone to render with the app's own icon and branding. That is a push that can claim to be
 * anything. The document is created by the client, so nothing between the two devices ever
 * decided what a notification was allowed to say — the bounds in `firestore.rules` capped its
 * *length*, which stops a wall of text on a lock screen and nothing else.
 *
 * Now a payload carries a [type] and the few facts that type needs, and the **receiving** device
 * writes the sentence. The frame — "New event", "Change requested", "Co-parent unlinked" — comes
 * from that device's own string resources and cannot be forged by anyone; only the names inside
 * it come from the payload, and those are names the co-parent is entitled to choose anyway,
 * because they are the event titles and child names they type into the app.
 *
 * ## Why this did not need the localization work it was blocked behind
 *
 * The backlog filed SEC-3 behind CQ-14 — service-layer strings are hardcoded English, so moving
 * composition server-side would need a locale stored per user and a string catalogue in
 * JavaScript. That reasoning assumed *server-side* was the only alternative to *sender-side*.
 * It is not: [buildFcmMessage in functions/index.js] already sends **data-only** messages with
 * no `notification` block, deliberately, so that the app always renders the push itself in every
 * app state. The receiving device is therefore already the one drawing the notification — and it
 * is the device that holds all five translations and knows which one the reader wants. Composing
 * there is both more secure and better localized than composing on a server, and it needs no new
 * profile field.
 *
 * ## The rule this pairs with
 *
 * `firestore.rules` refuses a client-written payload that carries `title` or `body` at all, and
 * accepts only the types listed in [CLIENT_TYPES]. The three server-only types below are
 * produced by Cloud Functions, which write as admin and bypass rules — so a client cannot forge
 * a pairing notification or a chat message it did not send. Both halves are needed: without the
 * type allow-list a client could send `chat_message` with any sender name; without the
 * title/body refusal it could go back to writing the sentence itself.
 */
object PushPayload {

    // ---- keys ----------------------------------------------------------------

    /** Which kind of notification this is; one of the constants below. */
    const val TYPE = "type"

    /** Display name of the parent who did the thing being announced. */
    const val ACTOR = "actorName"

    /**
     * What the notification is about, named by a person — an event's title, a child's name.
     *
     * One key rather than `eventTitle`/`childName`/… because the security rule has to bound
     * every free-text field a client can write, and a field the rule does not know about is a
     * field with no bound. One name keeps that list closed.
     */
    const val SUBJECT = "subject"

    /** The day a swap concerns, ISO `yyyy-MM-dd`. For a group, its first day. */
    const val DATE = "date"

    /**
     * How many days a grouped swap covers, as a decimal string.
     *
     * A count rather than a range string, and that is a rule interaction rather than a style
     * choice: `firestore.rules` bounds `date` at 20 characters, which `2026-09-05..2026-09-11`
     * exceeds. The receiving device turns the count into a sentence in its own language, where
     * Czech, Russian and Ukrainian each need three plural forms the sender could not know.
     */
    const val DAY_COUNT = "dayCount"

    /** Deep-link targets. Ids, not text — nothing renders them. */
    const val EVENT_ID = "eventId"
    const val CHILD_INFO_ID = "childInfoId"
    const val CHANGE_REQUEST_ID = "changeRequestId"
    const val CONVERSATION_ID = "conversationId"

    /** The first line or so of a chat message, written by the Cloud Function that saw it. */
    const val PREVIEW = "preview"

    // ---- types a client may produce -------------------------------------------

    const val EVENT_CREATED = "event_created"
    const val EVENT_UPDATED = "event_updated"
    const val EVENT_DELETED = "event_deleted"
    const val CHILD_INFO_UPDATED = "child_info_updated"
    const val CHANGE_REQUEST_CREATED = "change_request_created"
    const val CHANGE_REQUEST_ACCEPTED = "change_request_accepted"
    const val CHANGE_REQUEST_DECLINED = "change_request_declined"
    const val CHANGE_REQUEST_CANCELLED = "change_request_cancelled"
    const val CUSTODY_PROPOSAL_PROPOSED = "custody_proposal_proposed"
    const val CUSTODY_PROPOSAL_ACCEPTED = "custody_proposal_accepted"
    const val CUSTODY_PROPOSAL_DECLINED = "custody_proposal_declined"
    const val DAY_SWAP_OFFERED = "day_swap_offered"
    const val DAY_SWAP_ACCEPTED = "day_swap_accepted"
    const val DAY_SWAP_DECLINED = "day_swap_declined"

    /**
     * A run of days offered, agreed or turned down as one.
     *
     * Separate types rather than a count on the single-day ones so an older build, which has no
     * wording for these, drops them instead of announcing "1 day" for five. Dropping is the
     * intended behaviour for an unrecognised type; see the class KDoc.
     */
    const val DAY_SWAP_GROUP_OFFERED = "day_swap_group_offered"
    const val DAY_SWAP_GROUP_ACCEPTED = "day_swap_group_accepted"
    const val DAY_SWAP_GROUP_DECLINED = "day_swap_group_declined"

    /**
     * A change to how a shared expense divides between the two parents.
     *
     * No figure rides along, deliberately. A push saying "your co-parent proposes 70/30" would
     * put a number a reader may act on onto a lock screen, written by the other side and
     * unverifiable until the app is opened — and the app is where the proposal, with its
     * Confirm and Decline, actually is.
     */
    const val SPLIT_RATIO_PROPOSED = "split_ratio_proposed"
    const val SPLIT_RATIO_ACCEPTED = "split_ratio_accepted"
    const val SPLIT_RATIO_DECLINED = "split_ratio_declined"

    // ---- types only a Cloud Function may produce -------------------------------

    /** Queued by `acceptPairingInvitation`. */
    const val PAIRING_ACCEPTED = "pairing_accepted"

    /** Queued by `unpairCoParent`. */
    const val PAIRING_REMOVED = "pairing_removed"

    /** Queued by `onChatMessageCreated`, which is the only thing that has seen the message. */
    const val CHAT_MESSAGE = "chat_message"

    /**
     * Every type a client is allowed to enqueue.
     *
     * Kept as an allow-list rather than a deny-list of the three server-only types, and the
     * asymmetry is deliberate: forgetting to add a new *client* type here makes its push fail
     * to enqueue, which is annoying and obvious, while forgetting to add a new *server* type to
     * a deny-list would let any co-parent forge it, which is neither. `firestore.rules` holds
     * the same list — it is the half that is actually enforced; this one is here so a reader of
     * the sending code can see what the rule will accept.
     */
    val CLIENT_TYPES: Set<String> = setOf(
        EVENT_CREATED,
        EVENT_UPDATED,
        EVENT_DELETED,
        CHILD_INFO_UPDATED,
        CHANGE_REQUEST_CREATED,
        CHANGE_REQUEST_ACCEPTED,
        CHANGE_REQUEST_DECLINED,
        CHANGE_REQUEST_CANCELLED,
        CUSTODY_PROPOSAL_PROPOSED,
        CUSTODY_PROPOSAL_ACCEPTED,
        CUSTODY_PROPOSAL_DECLINED,
        DAY_SWAP_OFFERED,
        DAY_SWAP_ACCEPTED,
        DAY_SWAP_DECLINED,
        DAY_SWAP_GROUP_OFFERED,
        DAY_SWAP_GROUP_ACCEPTED,
        DAY_SWAP_GROUP_DECLINED,
        SPLIT_RATIO_PROPOSED,
        SPLIT_RATIO_ACCEPTED,
        SPLIT_RATIO_DECLINED
    )

    /**
     * The type for an event action, or null for an action nothing announces.
     *
     * Returns null rather than falling back to a generic type: a payload whose type the
     * receiving device cannot compose renders nothing at all now, so inventing one here would
     * queue a push that is guaranteed to be dropped on arrival.
     */
    fun eventType(action: String): String? = when (action) {
        "created" -> EVENT_CREATED
        "updated" -> EVENT_UPDATED
        "deleted" -> EVENT_DELETED
        else -> null
    }

    /** The type for a change-request action, or null for one nothing announces. */
    fun changeRequestType(action: String): String? = when (action) {
        "created" -> CHANGE_REQUEST_CREATED
        "accepted" -> CHANGE_REQUEST_ACCEPTED
        "declined" -> CHANGE_REQUEST_DECLINED
        "cancelled" -> CHANGE_REQUEST_CANCELLED
        else -> null
    }
}
