package com.coparently.app.domain.guests

import java.time.Duration
import java.time.Instant

/**
 * The lengths of guest access a parent can pick from when they invite somebody.
 *
 * A short list rather than a date picker, because the decision being made is "how long does
 * this person need to see my child's medical record" and the honest answers to that are
 * coarse. A picker would also make it possible to choose a date in the past, or in 2043, and
 * every layer below would then have to refuse it.
 *
 * There is deliberately no "no end" entry. That is the one option this feature must not
 * offer: a grant nobody ever revokes is the failure mode, and the whole expiry machinery —
 * [GuestGrantPolicy], the `guests` rule, the scheduled sweep — exists to make it impossible.
 */
enum class GuestAccessDuration(val duration: Duration) {

    /** A visit. */
    WEEK(Duration.ofDays(7)),

    /** The default — see [GuestGrantPolicy.DEFAULT_DURATION]. */
    MONTH(GuestGrantPolicy.DEFAULT_DURATION),

    /** A school term, roughly. The longest on offer. */
    QUARTER(Duration.ofDays(90));

    /** The expiry to put on a grant offered at [from], as epoch milliseconds. */
    fun expiryFrom(from: Instant): Long = from.plus(duration).toEpochMilli()

    companion object {
        /** What the invite sheet starts on. */
        val DEFAULT = MONTH
    }
}
