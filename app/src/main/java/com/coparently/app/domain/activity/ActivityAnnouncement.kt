package com.coparently.app.domain.activity

/**
 * What happened, as a fact rather than a sentence.
 *
 * **The two parents may not read the same language.** A card composed in the sender's locale is a
 * card the recipient may not be able to read, and the sender's language is not a property of the
 * message. So an announcement carries the facts and the *reader's* device turns them into a
 * sentence, through `stringResource`, in its own language.
 *
 * This replaces `RequestChangeViewModel.postChatMessage`, which built its card by concatenating
 * hardcoded English inside a ViewModel — the class of defect CLAUDE.md tracks, and one that would
 * have filled a Russian phone's chat with English the moment it was generalised to every change.
 *
 * @property kind What happened. Each member renders as one string resource; the mapping is a
 *   `when` with no `else`, so adding a member is a compile error until it is translated.
 * @property entityType What it happened to, so a tap can route without parsing [kind].
 * @property entityId The row to open on tap.
 * @property title The entity's own name — an event's title, an expense's description. Passed as a
 *   format argument, never concatenated into the sentence, because word order differs by language.
 * @property whenIso ISO date-time the change is about, where one applies. Formatted by the reader
 *   in their own locale and zone.
 * @property amount Formatted amount for an expense, or null. Already carries its currency symbol.
 * @property currency ISO currency code, or null. Kept beside [amount] so a reader can group by it
 *   without parsing the formatted string — the app never converts between currencies.
 */
data class ActivityAnnouncement(
    val kind: ActivityKind,
    val entityType: ActivityEntityType,
    val entityId: String,
    val title: String,
    val whenIso: String? = null,
    val amount: String? = null,
    val currency: String? = null
) {
    /**
     * The payload as a Firestore sub-map, keys stable from the start.
     *
     * A co-parent on an older build must keep reading the `messages` collection, so renaming a
     * key later is not available. Absent optionals are omitted rather than written as empty
     * strings, matching every other sub-map in this schema.
     */
    fun toMap(): Map<String, Any> = buildMap {
        put("kind", kind.name)
        put("entityType", entityType.name)
        put("entityId", entityId)
        put("title", title)
        whenIso?.let { put("whenIso", it) }
        amount?.let { put("amount", it) }
        currency?.let { put("currency", it) }
    }

    companion object {
        /**
         * The sub-map as a payload, or null when it cannot describe one.
         *
         * A payload whose [kind] or [entityType] this build does not recognise is **dropped**,
         * not defaulted. The message still renders: `Message.content` carries a plain-text
         * fallback for exactly this case, so an older build shows something rather than an empty
         * bubble or, worse, a confident sentence about the wrong thing.
         */
        fun fromMap(map: Map<*, *>?): ActivityAnnouncement? {
            if (map == null) return null
            val kind = (map["kind"] as? String)
                ?.let { name -> ActivityKind.entries.firstOrNull { it.name == name } }
                ?: return null
            val entityType = (map["entityType"] as? String)
                ?.let { name -> ActivityEntityType.entries.firstOrNull { it.name == name } }
                ?: return null
            val entityId = (map["entityId"] as? String)?.takeIf { it.isNotBlank() } ?: return null

            return ActivityAnnouncement(
                kind = kind,
                entityType = entityType,
                entityId = entityId,
                title = (map["title"] as? String).orEmpty(),
                whenIso = (map["whenIso"] as? String)?.takeIf { it.isNotBlank() },
                amount = (map["amount"] as? String)?.takeIf { it.isNotBlank() },
                currency = (map["currency"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * What an announcement is about, so a tap can route without inspecting [ActivityKind].
 *
 * A budget is deliberately absent: it is a private planning tool, not a shared fact, and a card
 * per budget edit would be noise in the one place both parents look.
 */
enum class ActivityEntityType {
    EVENT,
    EXPENSE,
    CHANGE_REQUEST,
    DAY_SWAP,
    CUSTODY_PROPOSAL
}

/**
 * Everything worth telling the co-parent about.
 *
 * Each member renders as exactly one string resource, and the mapping is a `when` with no `else` —
 * so adding a member here does not compile until it has been given a sentence in all five
 * locales. That is deliberate: a silent fallback is how an announcement ends up saying nothing.
 */
enum class ActivityKind {
    EVENT_CREATED,
    EVENT_UPDATED,
    EVENT_DELETED,
    EVENT_ACCEPTED,
    EVENT_DECLINED,
    CHANGE_REQUESTED,
    CHANGE_ACCEPTED,
    CHANGE_DECLINED,
    EXPENSE_ADDED,
    EXPENSE_UPDATED,
    EXPENSE_DELETED,
    DAY_SWAP_OFFERED,
    DAY_SWAP_ACCEPTED,
    DAY_SWAP_DECLINED,
    CUSTODY_PROPOSED,
    CUSTODY_ACCEPTED,
    CUSTODY_DECLINED
}
