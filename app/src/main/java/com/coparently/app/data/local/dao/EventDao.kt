package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coparently.app.data.local.entity.EventEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Data Access Object for EventEntity.
 * Provides methods to access event data from the Room database.
 */
@Dao
interface EventDao {
    /**
     * Gets all events as a Flow.
     */
    @Query("SELECT * FROM events WHERE deletedAtMillis IS NULL ORDER BY startDateTime ASC")
    fun getAllEvents(): Flow<List<EventEntity>>

    /**
     * Gets non-recurring events overlapping a date range (start before range end and
     * end — or start, for events without an end — after range start). This includes
     * multi-day and overnight events that begin before the range but reach into it.
     * Recurring events are fetched separately and expanded into occurrences.
     */
    @Query(
        """
        SELECT * FROM events
        WHERE deletedAtMillis IS NULL
        AND isRecurring = 0
        AND startDateTime <= :end
        AND (endDateTime IS NULL OR endDateTime >= :start)
        ORDER BY startDateTime ASC
        """
    )
    fun getSingleEventsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<EventEntity>>

    /**
     * Gets all recurring events that started on or before the given moment,
     * so their occurrences can be expanded into any later date range.
     */
    @Query(
        "SELECT * FROM events WHERE deletedAtMillis IS NULL " +
            "AND isRecurring = 1 AND startDateTime <= :end ORDER BY startDateTime ASC"
    )
    fun getRecurringEventsStartedBefore(end: LocalDateTime): Flow<List<EventEntity>>

    /**
     * Gets events overlapping a specific calendar date. A multi-day or overnight
     * event that starts on an earlier day but reaches into [date] is included, so
     * it stays visible on every day it spans (not only its start day).
     */
    @Query(
        """
        SELECT * FROM events
        WHERE deletedAtMillis IS NULL
        AND date(startDateTime) <= date(:date)
        AND (endDateTime IS NULL OR date(endDateTime) >= date(:date))
        ORDER BY startDateTime ASC
        """
    )
    fun getEventsByDate(date: LocalDateTime): Flow<List<EventEntity>>

    /**
     * Gets an event by ID, **including a pending tombstone**.
     *
     * The one read that is deliberately not filtered on `deletedAtMillis`. Its callers are the
     * sync and delete paths, and for them "there is no such row" and "there is a row this
     * device has deleted" are opposite answers: the downstream half must recognise a local
     * tombstone rather than treat the id as unknown and insert the remote document over it,
     * which would resurrect exactly the event the parent just deleted. A caller answering a
     * *user's* question filters at the repository boundary instead — see
     * `EventRepositoryImpl.getEventById`.
     */
    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getEventById(id: String): EventEntity?

    /**
     * Gets events for a specific parent owner.
     */
    @Query(
        "SELECT * FROM events WHERE deletedAtMillis IS NULL " +
            "AND parentOwner = :parentOwner ORDER BY startDateTime ASC"
    )
    fun getEventsByParent(parentOwner: String): Flow<List<EventEntity>>

    /**
     * Inserts a new event.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)

    /**
     * Inserts multiple events.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<EventEntity>)

    /**
     * Updates an existing event.
     */
    @Update
    suspend fun updateEvent(event: EventEntity)

    /**
     * Deletes an event by ID.
     */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEventById(id: String)

    /**
     * Gets all events that have not been synced to Firestore — **tombstones included**.
     *
     * This is the outbox, and a pending deletion is the one thing in it that must never be
     * filtered out: a tombstone that no upload pass picks up is a delete that stays on one
     * phone forever, which is the whole of CQ-3.
     */
    @Query("SELECT * FROM events WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedEvents(): List<EventEntity>

    /**
     * Marks an event as synced to Firestore.
     */
    @Query("UPDATE events SET syncedToFirestore = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    /**
     * Upserts an event (insert or update if exists).
     */
    @androidx.room.Upsert
    suspend fun upsertEvent(event: EventEntity)

    /**
     * Batch insert events.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventsBatch(events: List<EventEntity>)

    /**
     * Batch delete events.
     */
    @Delete
    suspend fun deleteEventsBatch(events: List<EventEntity>)

    /**
     * Gets events for a specific child with pagination.
     */
    @Query("""
        SELECT * FROM events
        WHERE deletedAtMillis IS NULL
        AND parentOwner = :parentOwner
        AND startDateTime BETWEEN :start AND :end
        ORDER BY startDateTime ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getEventsForParentPaginated(
        parentOwner: String,
        start: LocalDateTime,
        end: LocalDateTime,
        limit: Int,
        offset: Int
    ): List<EventEntity>

    /**
     * Gets count of events for a specific parent in date range.
     */
    @Query("""
        SELECT COUNT(*) FROM events
        WHERE deletedAtMillis IS NULL
        AND parentOwner = :parentOwner
        AND startDateTime BETWEEN :start AND :end
    """)
    suspend fun getEventsCountForParent(
        parentOwner: String,
        start: LocalDateTime,
        end: LocalDateTime
    ): Int

    /**
     * Re-stamps the parent slot on events this user created. Used when pairing moves this
     * device from one slot to the other; without it, every event the accepter created before
     * pairing reads as the co-parent's.
     *
     * **`syncedToFirestore = 0` is part of the statement, not housekeeping.** The re-stamp
     * writes Room directly, so without it `SyncService.syncEvents` would leave these rows out
     * of its upload half (it uploads `getUnsyncedEvents()` only) and then overwrite them in its
     * download half, which REPLACEs every row it receives with the document's still-stale
     * `parentOwner`. Both halves run in the same `performFullSync` pass, one step after the
     * re-stamp itself — so the whole re-stamp was undone seconds later, silently, and never
     * retried, because `ParentSlotMigrator.reslot` had already advanced the slot marker.
     * Clearing the flag in the same `UPDATE` matches exactly the rows that changed, republishes
     * them under the new slot, and — because the upload recomputes the audience — is also what
     * finally delivers pre-pairing events to the co-parent at all.
     */
    @Query(
        "UPDATE events SET parentOwner = :to, syncedToFirestore = 0 " +
            "WHERE parentOwner = :from AND createdByFirebaseUid = :myUid AND deletedAtMillis IS NULL"
    )
    suspend fun reslotOwner(from: String, to: String, myUid: String): Int

    /**
     * Re-stamps a recorded pickup confirmation for the same reason as [reslotOwner], and clears
     * the sync flag for the same reason: `pickupConfirmedBy` is part of the event document, so
     * a re-stamp the upload half never sees is a re-stamp the download half reverts.
     */
    @Query(
        "UPDATE events SET pickupConfirmedBy = :to, syncedToFirestore = 0 " +
            "WHERE pickupConfirmedBy = :from AND createdByFirebaseUid = :myUid AND deletedAtMillis IS NULL"
    )
    suspend fun reslotPickup(from: String, to: String, myUid: String): Int

    /**
     * Re-queues every non-private event this user created for upload, by clearing the flag
     * `SyncService.syncEvents` selects on.
     *
     * The upload half recomputes `sharedWith` from live state for every row it uploads, so
     * clearing the flag is what republishes an event under the audience the account has *now*.
     * Without it, an event created while unpaired keeps the one-uid audience it was uploaded
     * with forever: `sharedWith` is never recomputed for a row already marked synced.
     *
     * Only [reslotOwner] used to have this effect, and only as a side effect, so it reached
     * only the parent whose slot moved — the accepter. The inviter keeps their slot
     * (`PairingViewModel.withSlotReslot`), so their whole pre-pairing history, Google imports
     * included, stayed invisible to the co-parent.
     *
     * `isPrivate = 0` is part of the statement rather than a filter applied to its result: a
     * row with the flag cleared is a row queued for upload, and a private event must never be
     * queued at all, not even for one pass that later drops it.
     *
     * Rows with a null `createdByFirebaseUid` — old enough to predate the column being stamped
     * — are not matched by `= :myUid` and are deliberately left alone: nothing distinguishes
     * this user's un-stamped event from anybody else's, and a statement that guessed would
     * publish the wrong person's history.
     *
     * `deletedAtMillis IS NULL` excludes pending tombstones. They are queued already — a
     * tombstone is a row with the flag cleared, by definition — so this changes nothing about
     * what gets uploaded; what it changes is the returned count, which is logged as "how many
     * events were republished for the co-parent" and should not include events nobody will
     * ever see.
     *
     * @param myUid Firebase UID of the signed-in user.
     * @return How many rows were re-queued.
     */
    @Query(
        "UPDATE events SET syncedToFirestore = 0 " +
            "WHERE createdByFirebaseUid = :myUid AND isPrivate = 0 AND deletedAtMillis IS NULL"
    )
    suspend fun markOwnEventsUnsynced(myUid: String): Int

    /**
     * Marks an event deleted, and queues the deletion for upload.
     *
     * `syncedToFirestore = 0` is part of the statement rather than a separate write, for the
     * same reason it is part of [reslotOwner]: a row the upload half does not select is a change
     * that never leaves the device, and here that change is the deletion itself.
     *
     * `deletedAtMillis IS NULL` in the WHERE clause makes a second delete a no-op instead of
     * re-dating the first one. Two deletes of the same event are not hypothetical — a failed
     * upload leaves the row in place, visible to nothing, and the sync retries it.
     *
     * `updatedAt` is deliberately **not** bumped. It is a naive `LocalDateTime` with the
     * cross-time-zone ordering defect SEC-4 describes, and nothing about a deletion is decided
     * by comparing it: see `SyncService.syncEvents`, where a tombstone wins outright rather
     * than by timestamp.
     *
     * @return 1 if this call is what deleted the event, 0 if it was already deleted or absent.
     */
    @Query(
        "UPDATE events SET deletedAtMillis = :deletedAtMillis, syncedToFirestore = 0 " +
            "WHERE id = :id AND deletedAtMillis IS NULL"
    )
    suspend fun markDeleted(id: String, deletedAtMillis: Long): Int
}
