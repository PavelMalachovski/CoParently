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
    @Query("SELECT * FROM events ORDER BY startDateTime ASC")
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
        WHERE isRecurring = 0
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
    @Query("SELECT * FROM events WHERE isRecurring = 1 AND startDateTime <= :end ORDER BY startDateTime ASC")
    fun getRecurringEventsStartedBefore(end: LocalDateTime): Flow<List<EventEntity>>

    /**
     * Gets events overlapping a specific calendar date. A multi-day or overnight
     * event that starts on an earlier day but reaches into [date] is included, so
     * it stays visible on every day it spans (not only its start day).
     */
    @Query(
        """
        SELECT * FROM events
        WHERE date(startDateTime) <= date(:date)
        AND (endDateTime IS NULL OR date(endDateTime) >= date(:date))
        ORDER BY startDateTime ASC
        """
    )
    fun getEventsByDate(date: LocalDateTime): Flow<List<EventEntity>>

    /**
     * Gets an event by ID.
     */
    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getEventById(id: String): EventEntity?

    /**
     * Gets events for a specific parent owner.
     */
    @Query("SELECT * FROM events WHERE parentOwner = :parentOwner ORDER BY startDateTime ASC")
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
     * Deletes an event.
     */
    @Delete
    suspend fun deleteEvent(event: EventEntity)

    /**
     * Deletes an event by ID.
     */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEventById(id: String)

    /**
     * Gets all events that have not been synced to Firestore.
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
        WHERE parentOwner = :parentOwner
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
        WHERE parentOwner = :parentOwner
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
            "WHERE parentOwner = :from AND createdByFirebaseUid = :myUid"
    )
    suspend fun reslotOwner(from: String, to: String, myUid: String): Int

    /**
     * Re-stamps a recorded pickup confirmation for the same reason as [reslotOwner], and clears
     * the sync flag for the same reason: `pickupConfirmedBy` is part of the event document, so
     * a re-stamp the upload half never sees is a re-stamp the download half reverts.
     */
    @Query(
        "UPDATE events SET pickupConfirmedBy = :to, syncedToFirestore = 0 " +
            "WHERE pickupConfirmedBy = :from AND createdByFirebaseUid = :myUid"
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
     * @param myUid Firebase UID of the signed-in user.
     * @return How many rows were re-queued.
     */
    @Query(
        "UPDATE events SET syncedToFirestore = 0 " +
            "WHERE createdByFirebaseUid = :myUid AND isPrivate = 0"
    )
    suspend fun markOwnEventsUnsynced(myUid: String): Int
}

