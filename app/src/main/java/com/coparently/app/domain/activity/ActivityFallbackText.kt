package com.coparently.app.domain.activity

/**
 * The plain-text sentence an announcement carries in `Message.content`.
 *
 * **This is not a localisation path, and must not become one.** The real card is composed by the
 * *reading* device from [ActivityAnnouncement], in the reader's own language — that is the whole
 * point of the payload. This text exists for one case: a co-parent on a build that predates
 * `MessageType.ACTIVITY`, whose app degrades the unknown type to a plain text bubble and shows
 * `content`. Without it they would see an empty bubble.
 *
 * It is therefore in the app's **base locale** (English, which is what `values/` is), always, for
 * everyone — not in the sender's locale and not in the reader's. Neither would be more correct:
 * the sender's language is not a property of the message, and the reader's is unknown to the
 * sender. A single fixed language at least makes the fallback predictable, and it disappears
 * entirely once both phones carry this build.
 *
 * It lives here rather than in `res/values` deliberately: a repository has no `Context`, and
 * CLAUDE.md forbids injecting one to reach a string. These are not strings that should have been
 * localised — they are the one place localisation is explicitly not wanted.
 */
object ActivityFallbackText {

    /** The base-locale sentence for [announcement]. */
    fun of(announcement: ActivityAnnouncement): String {
        val prefix = when (announcement.kind) {
            ActivityKind.EVENT_CREATED -> "New event"
            ActivityKind.EVENT_UPDATED -> "Event changed"
            ActivityKind.EVENT_DELETED -> "Event removed"
            ActivityKind.EVENT_ACCEPTED -> "Event accepted"
            ActivityKind.EVENT_DECLINED -> "Event declined"
            ActivityKind.CHANGE_REQUESTED -> "New time proposed"
            ActivityKind.CHANGE_ACCEPTED -> "New time agreed"
            ActivityKind.CHANGE_DECLINED -> "New time turned down"
            ActivityKind.EXPENSE_ADDED -> "New expense"
            ActivityKind.EXPENSE_UPDATED -> "Expense changed"
            ActivityKind.EXPENSE_DELETED -> "Expense removed"
            ActivityKind.DAY_SWAP_OFFERED -> "Day swap offered"
            ActivityKind.DAY_SWAP_ACCEPTED -> "Day swap agreed"
            ActivityKind.DAY_SWAP_DECLINED -> "Day swap turned down"
        }
        return if (announcement.title.isBlank()) prefix else "$prefix: ${announcement.title}"
    }
}
