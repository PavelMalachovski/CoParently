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
     * Gets events for a specific date range.
     */
    @Query("SELECT * FROM events WHERE startDateTime >= :start AND startDateTime <= :end ORDER BY startDateTime ASC")
    fun getEventsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<EventEntity>>

    /**
     * Gets events for a specific date.
     */
    @Query("SELECT * FROM events WHERE date(startDateTime) = date(:date) ORDER BY startDateTime ASC")
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
}

