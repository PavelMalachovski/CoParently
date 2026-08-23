package com.coparently.app.data.sync

/**
 * Who may read a child-info document.
 *
 * `child_info` is gated on `sharedWith` in `firestore.rules`, and until this existed the uploader
 * published only the creator and the last modifier — so a paired parent could never see child
 * information the other had entered. The Cloud Function that revokes this access on unpair
 * (`SHARED_AUDIENCE_COLLECTIONS`) has always covered `child_info`: the revocation was written for
 * a grant that was never made.
 */
object ChildInfoAudience {

    /**
     * Derives the audience for an upload, from live state only.
     *
     * Deliberately **not** intersected with a stored list the way `SyncService.shareTargets` is
     * for events. That intersection exists because `EventEntity` keeps its own copy of the
     * audience, which the server's unpair sweep never narrows; `ChildInfoEntity` keeps no such
     * copy, so deriving from live state gives the same protection for free. An ex-partner is
     * absent from the very next upload simply because [partnerId] is null.
     *
     * **Guests are part of the audience and must be passed in.** They are the one entry here
     * that is not derived from live pairing state: a guest is a fact about *this document*, so
     * an upload that forgot them would drop a grandparent out of `sharedWith` and end their
     * access at the next background sync, silently and long before the grant said it would.
     * Pass only the grants that are still active — an expired guest leaving the audience on the
     * next upload is the sweep's job being done early, which is the direction to fail in.
     *
     * @param userId The uploading user's Firebase UID
     * @param creatorUid The document's `createdByFirebaseUid`, or null if it never synced
     * @param partnerId The uploader's **current** co-parent, or null when unpaired
     * @param guestUids UIDs of guests whose grants are still active; empty when there are none
     * @return The UIDs to publish, de-duplicated, uploader first
     */
    fun entitled(
        userId: String,
        creatorUid: String?,
        partnerId: String?,
        guestUids: List<String> = emptyList()
    ): List<String> =
        (listOf(userId) + listOfNotNull(creatorUid, partnerId) + guestUids)
            .filter { it.isNotBlank() }
            .distinct()
}
