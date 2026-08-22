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
     * @param userId The uploading user's Firebase UID
     * @param creatorUid The document's `createdByFirebaseUid`, or null if it never synced
     * @param partnerId The uploader's **current** co-parent, or null when unpaired
     * @return The UIDs to publish, de-duplicated, uploader first
     */
    fun entitled(userId: String, creatorUid: String?, partnerId: String?): List<String> =
        (listOf(userId) + listOfNotNull(creatorUid, partnerId))
            .filter { it.isNotBlank() }
            .distinct()
}
