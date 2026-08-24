package com.coparently.app.data.sync

/**
 * What a deleted document looks like on the wire, defined once.
 *
 * A delete used to be a `document().delete()` and nothing else, which is why a co-parent kept
 * cancelled events forever (CQ-3): the deleting device removed its own row, the document
 * vanished, and the *downstream* half of the sync only ever inserts — no branch has ever
 * removed a local row, so there was nothing for the other phone to act on. A vanished document
 * is not a fact that can be delivered. A **tombstone** is: the document stays, gains
 * [DELETED_AT_MILLIS], and reaches the co-parent through the same query that delivers every
 * other change.
 *
 * Deleting the document outright cannot be rescued by any amount of downstream cleverness,
 * because "absent from the snapshot" is not the same question as "deleted". Events are read
 * with `whereArrayContains("sharedWith", uid)`, so a document also leaves a user's snapshot
 * when the unpair sweep narrows its audience; a download window (CQ-5) will make everything
 * outside it absent as well; and a private event is absent by construction. A reconciliation
 * pass that deleted whatever it did not see would take the whole calendar with it the first
 * time one of those happened.
 *
 * ## Epoch millis, and why not `updatedAt`
 *
 * [DELETED_AT_MILLIS] is epoch millis, the same decision as `Message.sentAtMillis`: two parents
 * can be in different time zones, and this value crosses between their phones. The events
 * schema's own `updatedAt` is a naive `LocalDateTime` with the ordering defect SEC-4 describes,
 * so nothing here is decided by comparing it — see [com.coparently.app.data.sync.SyncService]
 * for the rule that replaces it.
 *
 * The field name matches `expiresAtMillis` in the guest grants, which is the existing
 * convention in `firestore.rules` for a wire-format epoch.
 *
 * ## Undo
 *
 * A tombstone is reversible, and nothing here does the reversing. The Undo snackbars on the
 * event and expense lists re-create the captured row through the ordinary add path, which
 * inserts over the Room row (`OnConflictStrategy.REPLACE`, and the domain model carries no
 * deletion field, so the local tombstone is simply gone) and writes the remote document with
 * `set()` — a full replace, which drops [DELETED_AT_MILLIS] with it. So an undone delete looks
 * to every device exactly like an event that was never deleted, including a co-parent who
 * already applied the tombstone and dropped their row: their next sync sees a live document
 * with no local copy and inserts it back. Keep the add paths on `set()` for that reason — an
 * `update()` there would leave the tombstone standing on a document the app believes is alive.
 */
object Tombstone {

    /** When the document was deleted, epoch millis. Absent on a live document. */
    const val DELETED_AT_MILLIS = "deletedAtMillis"

    /** Firebase UID of whoever deleted it — for the sweep's audit line, not for any decision. */
    const val DELETED_BY = "deletedBy"

    /**
     * The fields that turn a live document into a tombstone.
     *
     * Written with `update()` rather than `set()` **on purpose**: the read rules for both
     * collections are keyed on fields of the existing document (`createdByFirebaseUid`,
     * `sharedWith`), so a tombstone that replaced the document would be a tombstone nobody is
     * allowed to read — a delete that is delivered to no one, which is the bug this exists to
     * fix, wearing a different hat.
     */
    fun fields(deletedAtMillis: Long, deletedBy: String): Map<String, Any> = mapOf(
        DELETED_AT_MILLIS to deletedAtMillis,
        DELETED_BY to deletedBy
    )

    /**
     * The deletion time carried by a document snapshot, or null if it is alive.
     *
     * Firestore hands back a `Long` for an integer field, but the map is typed `Any?` and a
     * legacy document could hold anything, so this reads through [Number] rather than casting.
     * A non-positive value is read as *alive*: zero is what a missing field degrades to in
     * arithmetic, and a tombstone is too destructive to infer from a default.
     */
    fun deletedAtMillisIn(data: Map<String, Any?>): Long? =
        (data[DELETED_AT_MILLIS] as? Number)?.toLong()?.takeIf { it > 0L }

    /** Whether a document snapshot is a tombstone. */
    fun isDeleted(data: Map<String, Any?>): Boolean = deletedAtMillisIn(data) != null
}
