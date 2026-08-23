package com.coparently.app.data.sync

/**
 * Who may read a pet document.
 *
 * `pets` is gated on `sharedWith` in `firestore.rules`, exactly like `child_info`, and the
 * audience is derived from **live state only** for the same reason [ChildInfoAudience]
 * documents at length: `PetEntity` keeps no stored copy of the audience, so an ex-partner is
 * absent from the very next upload simply because `partnerId` is null. Pets have no guest
 * grants — a separate object rather than a call into [ChildInfoAudience] with an empty list,
 * so the child record's policy can grow guest rules without silently changing this one.
 */
object PetAudience {

    /**
     * Derives the audience for an upload.
     *
     * @param userId The uploading user's Firebase UID
     * @param creatorUid The document's `createdByFirebaseUid`, or null if it never synced
     * @param partnerId The uploader's **current** co-parent, or null when unpaired
     * @return The UIDs to publish, de-duplicated, uploader first
     */
    fun entitled(
        userId: String,
        creatorUid: String?,
        partnerId: String?
    ): List<String> =
        (listOf(userId) + listOfNotNull(creatorUid, partnerId))
            .filter { it.isNotBlank() }
            .distinct()
}
