package com.coparently.app.data.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a deletion off the wire.
 *
 * This is a two-line function guarding a destructive act: everything [Tombstone.isDeleted]
 * returns true for is a row that gets removed from a parent's calendar without being asked
 * again. So the cases that matter are not the happy one — they are every shape a document can
 * arrive in that is *not* a deletion and must not be read as one.
 */
class TombstoneTest {

    private val deletedAt = 1_787_000_000_000L

    @Test
    fun `a document with no deletion field is alive`() {
        val alive = mapOf<String, Any?>("id" to "e1", "title" to "Handover")

        assertFalse(Tombstone.isDeleted(alive))
        assertNull(Tombstone.deletedAtMillisIn(alive))
    }

    @Test
    fun `a document carrying a deletion time is a tombstone`() {
        val data = mapOf<String, Any?>("id" to "e1", Tombstone.DELETED_AT_MILLIS to deletedAt)

        assertTrue(Tombstone.isDeleted(data))
        assertEquals(deletedAt, Tombstone.deletedAtMillisIn(data))
    }

    @Test
    fun `a Long written by Firestore and an Int written by anything else read the same`() {
        // Firestore hands integers back as Long, but the map is typed `Any?` and the emulator,
        // a test fixture or an older client can put an Int there. Casting to Long directly
        // returns null for an Int — which would read a real deletion as a live document.
        val asInt = mapOf<String, Any?>(Tombstone.DELETED_AT_MILLIS to 1_700_000_000)

        assertEquals(1_700_000_000L, Tombstone.deletedAtMillisIn(asInt))
        assertTrue(Tombstone.isDeleted(asInt))
    }

    @Test
    fun `zero is alive, not deleted at the epoch`() {
        // Zero is what a missing field degrades to in arithmetic, and it is the value a rule or
        // a serializer supplies when it has nothing. Reading it as "deleted on 1 January 1970"
        // would delete the row.
        val zero = mapOf<String, Any?>(Tombstone.DELETED_AT_MILLIS to 0L)

        assertFalse(Tombstone.isDeleted(zero))
        assertNull(Tombstone.deletedAtMillisIn(zero))
    }

    @Test
    fun `a negative time is alive too`() {
        val negative = mapOf<String, Any?>(Tombstone.DELETED_AT_MILLIS to -1L)

        assertFalse(Tombstone.isDeleted(negative))
    }

    @Test
    fun `an explicit null is alive`() {
        // A client that writes the key with no value, or a document whose tombstone was undone
        // by setting the field to null, must both read as a document nobody deleted.
        val nulled = mapOf<String, Any?>(Tombstone.DELETED_AT_MILLIS to null)

        assertFalse(Tombstone.isDeleted(nulled))
    }

    @Test
    fun `a value of the wrong type is alive rather than an exception`() {
        // A string here means somebody wrote the field wrong. Neither answer is satisfying, but
        // only one of them silently removes a parent's event on the strength of a malformed
        // field — and a throw inside the sync loop would take the rest of the download with it.
        val wrong = mapOf<String, Any?>(Tombstone.DELETED_AT_MILLIS to "2026-08-24T10:00:00")

        assertFalse(Tombstone.isDeleted(wrong))
        assertNull(Tombstone.deletedAtMillisIn(wrong))
    }

    @Test
    fun `the fields written are exactly the two the sweep and the reader need`() {
        val fields = Tombstone.fields(deletedAtMillis = deletedAt, deletedBy = "uid-a")

        assertEquals(
            mapOf(Tombstone.DELETED_AT_MILLIS to deletedAt, Tombstone.DELETED_BY to "uid-a"),
            fields
        )
        // Nothing else: the write is an `update`, so every key here overwrites a field of a live
        // document. A tombstone that also rewrote `sharedWith` or `createdByFirebaseUid` would
        // be one the co-parent is no longer allowed to read.
        assertEquals(2, fields.size)
    }
}
