package com.coparently.app.domain.custody

/**
 * The decision behind the "the schedule changed under you" banner, kept out of Compose and the
 * ViewModel so it can be unit tested without a composition or a `StateFlow`.
 *
 * Custody is last-write-wins with no consent step (see `CustodyModelRepository`), consistent
 * with how shared events already behave. That is what makes this decision matter: nothing stops
 * either parent's write from overwriting the other's, so a losing write must not also disappear
 * silently — a custody pattern is the thing a separated parent plans their life around. Equally,
 * it must never announce this device's *own* write as if it were the co-parent's — that failure
 * mode is just as much "not honest" as the silent one, only in the other direction.
 */
object CustodyChangeAnnouncement {

    /**
     * The remote change to announce, or null when there is nothing worth telling this user
     * about.
     *
     * Null when:
     * - [shared] itself is null — nothing has ever been shared.
     * - [parentsLoaded] is false — this device does not yet know its own uid, so it cannot rule
     *   out that the change is its own echo. `CustodyModelRepository`'s pair resolution is
     *   Room-only and reliably faster than `Parents` resolving three Firestore pairing
     *   listeners, so on a fresh subscription (cold start, or reopening Calendar after the
     *   pairing listener has been torn down) the echo of this device's own just-made write can
     *   arrive before `myUid` is known. Treating "not loaded" as "definitely not mine" would
     *   announce the user's own edit as the co-parent's; treating it as "nothing to say *yet*"
     *   costs one moment's delay for a genuinely remote change and nothing else, since `Parents`
     *   re-emits the instant it resolves.
     * - [shared]'s [SharedCustody.lastModifiedBy] equals [myUid] — this device's own write,
     *   including the echo the shared listener gets back after every push, and the resend
     *   `CustodyModelRepository` performs when recovering a write it finds was lost (still
     *   stamped with this device's own uid, precisely so it is never mistaken for the
     *   co-parent's change). [myUid] can be null even once [parentsLoaded] is true — an account
     *   with no Room profile row stays that way forever — and in that case a real write is never
     *   suppressed by uid; there is nothing more to wait for once loaded is true, unlike the
     *   not-yet-loaded case above.
     * - [shared]'s [SharedCustody.lastModifiedAt] equals [dismissedLastModifiedAt] — the user
     *   already acknowledged this exact change. A later change carries a different
     *   `lastModifiedAt` and is announced again.
     *
     * Otherwise [shared] is returned unchanged — including when `lastModifiedBy` matches
     * neither parent. Naming who changed it is a separate question the caller answers by
     * resolving the uid directly against the known parents (never through a slot — see
     * `parentLabelByUid`): an unresolvable uid is still a real change, so the banner still shows,
     * with the unknown-parent fallback standing in for the name.
     *
     * @param myUid This device's own uid (`Parents.me`'s uid), or null.
     * @param parentsLoaded Whether `Parents` has produced a real answer yet (`Parents.loaded`),
     *   as opposed to the synthetic starting value every subscription begins from.
     */
    fun toAnnounce(
        shared: SharedCustody?,
        myUid: String?,
        parentsLoaded: Boolean,
        dismissedLastModifiedAt: String?
    ): SharedCustody? {
        if (shared == null || !parentsLoaded) return null
        val isOwnWrite = myUid != null && shared.lastModifiedBy == myUid
        val isAlreadyDismissed = shared.lastModifiedAt == dismissedLastModifiedAt
        return shared.takeUnless { isOwnWrite || isAlreadyDismissed }
    }
}
