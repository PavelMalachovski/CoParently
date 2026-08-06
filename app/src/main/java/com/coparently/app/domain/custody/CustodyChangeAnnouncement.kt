package com.coparently.app.domain.custody

/**
 * The decision behind the "the schedule changed under you" banner, kept out of Compose and the
 * ViewModel so it can be unit tested without a composition or a `StateFlow`.
 *
 * Custody is last-write-wins with no consent step (see `CustodyModelRepository`), consistent
 * with how shared events already behave. That is what makes this decision matter: nothing stops
 * either parent's write from overwriting the other's, so a losing write must not also disappear
 * silently — a custody pattern is the thing a separated parent plans their life around.
 */
object CustodyChangeAnnouncement {

    /**
     * The remote change to announce, or null when there is nothing worth telling this user
     * about.
     *
     * Null when:
     * - [shared] itself is null — nothing has ever been shared.
     * - [shared]'s [SharedCustody.lastModifiedBy] equals [myUid] — this device's own write,
     *   including the echo the shared listener gets back after every push, and the resend
     *   `CustodyModelRepository` performs when recovering a write it finds was lost (still
     *   stamped with this device's own uid, precisely so it is never mistaken for the
     *   co-parent's change).
     * - [shared]'s [SharedCustody.lastModifiedAt] equals [dismissedLastModifiedAt] — the user
     *   already acknowledged this exact change. A later change carries a different
     *   `lastModifiedAt` and is announced again.
     *
     * Otherwise [shared] is returned unchanged — including when `lastModifiedBy` matches
     * neither parent. Naming who changed it is a separate question the caller answers by
     * resolving the uid through `Parents.roleByUid` and `parentLabel`: an unresolvable uid is
     * still a real change, so the banner still shows, with the unknown-parent fallback standing
     * in for the name.
     *
     * @param myUid This device's own uid (`Parents.me`'s), or null before it has loaded. A null
     *   [myUid] never suppresses a change — until this device knows its own uid, no write can be
     *   confirmed to be its own.
     */
    fun toAnnounce(
        shared: SharedCustody?,
        myUid: String?,
        dismissedLastModifiedAt: String?
    ): SharedCustody? {
        if (shared == null) return null
        val isOwnWrite = myUid != null && shared.lastModifiedBy == myUid
        val isAlreadyDismissed = shared.lastModifiedAt == dismissedLastModifiedAt
        return shared.takeUnless { isOwnWrite || isAlreadyDismissed }
    }
}
