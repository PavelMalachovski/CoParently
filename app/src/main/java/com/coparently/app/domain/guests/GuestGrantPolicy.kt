package com.coparently.app.domain.guests

import java.time.Duration
import java.time.Instant

/**
 * Whether a [GuestGrant] is still good, and how long a new one lasts.
 *
 * Pure and free of Android, because the same question is asked in three places that cannot share
 * anything else: the app deciding what to show, `firestore.rules` deciding what to serve, and the
 * scheduled sweep deciding what to remove. Only the first can call this — the other two are
 * written in other languages — so this file is also the **statement of what the other two must
 * agree with**, and every rule below says which of the three it binds. The `guests` block in
 * `firestore.rules` and `sweepExpiredGuests` in `functions/index.js` both point back here.
 *
 * **Fail closed, always.** Every answer here decides access to a child's medical record. An
 * expiry this cannot make sense of is treated as already past, never as absent: reading a missing
 * or nonsensical value as "no expiry" would turn a grandparent's weekend into a permanent grant,
 * and it would do it silently.
 */
object GuestGrantPolicy {

    /**
     * How long a grant lasts unless the parent chooses otherwise.
     *
     * Thirty days. Item 15's example is a weekend with a grandmother, not a lifetime, and a
     * month is long enough that nobody has to renew it mid-visit while being short enough that
     * a forgotten grant ends on its own.
     */
    val DEFAULT_DURATION: Duration = Duration.ofDays(30)

    /**
     * Whether [grant] may still be used at [at].
     *
     * The boundary belongs to the closed side: a grant expiring at noon is not active at noon.
     * An access token is the wrong place to round in the holder's favour, and it also means the
     * rule and the sweep cannot disagree about the single instant they both test — both
     * implement this same strict `<` against `expiresAtMillis`.
     *
     * A non-positive expiry is not a time at all: it is a field that was absent, or defaulted, or
     * written by something that did not know it had to fill it in. Treated as expired.
     */
    fun isActive(grant: GuestGrant, at: Instant): Boolean =
        grant.expiresAtMillis > 0L && at.toEpochMilli() < grant.expiresAtMillis

    /** The grants of a record that are still good at [at], in the order given. */
    fun active(grants: List<GuestGrant>, at: Instant): List<GuestGrant> =
        grants.filter { isActive(it, at) }

    /**
     * The expiry to stamp on a grant made at [from], as epoch milliseconds.
     *
     * Millis rather than an ISO string because the value is compared by a phone, by a Firestore
     * rule and by a scheduled function, and rules cannot parse a string into a timestamp — see
     * [GuestGrant.expiresAtMillis].
     */
    fun defaultExpiry(from: Instant): Long = from.plus(DEFAULT_DURATION).toEpochMilli()
}
