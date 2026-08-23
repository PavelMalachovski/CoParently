package com.coparently.app.presentation.chat

import androidx.annotation.StringRes
import com.coparently.app.R
import com.coparently.app.domain.activity.ActivityKind

/**
 * Turns an announcement's [ActivityKind] into the sentence *this* device speaks.
 *
 * The whole point of the payload is that the sentence is composed by the reader, not the sender:
 * the two parents may not read the same language, and a card written in the sender's locale is a
 * card the recipient may not be able to read.
 *
 * `when` with **no `else`**, deliberately. Adding a member to [ActivityKind] then fails to
 * compile until it has been given a sentence — and, because every key must exist in all five
 * locale files, until it has been translated. A silent fallback is how an announcement ends up
 * saying nothing.
 */
object ActivityCardText {

    /**
     * The string resource for [kind]. Each takes the entity's own name as `%1$s`; it is passed as
     * a format argument and never concatenated, because word order differs by language.
     */
    @StringRes
    fun resourceFor(kind: ActivityKind): Int = when (kind) {
        ActivityKind.EVENT_CREATED -> R.string.activity_event_created
        ActivityKind.EVENT_UPDATED -> R.string.activity_event_updated
        ActivityKind.EVENT_DELETED -> R.string.activity_event_deleted
        ActivityKind.EVENT_ACCEPTED -> R.string.activity_event_accepted
        ActivityKind.EVENT_DECLINED -> R.string.activity_event_declined
        ActivityKind.CHANGE_REQUESTED -> R.string.activity_change_requested
        ActivityKind.CHANGE_ACCEPTED -> R.string.activity_change_accepted
        ActivityKind.CHANGE_DECLINED -> R.string.activity_change_declined
        ActivityKind.EXPENSE_ADDED -> R.string.activity_expense_added
        ActivityKind.EXPENSE_UPDATED -> R.string.activity_expense_updated
        ActivityKind.EXPENSE_DELETED -> R.string.activity_expense_deleted
        ActivityKind.DAY_SWAP_OFFERED -> R.string.activity_day_swap_offered
        ActivityKind.DAY_SWAP_ACCEPTED -> R.string.activity_day_swap_accepted
        ActivityKind.DAY_SWAP_DECLINED -> R.string.activity_day_swap_declined
    }
}
