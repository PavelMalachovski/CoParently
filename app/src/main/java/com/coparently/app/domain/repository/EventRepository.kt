package com.coparently.app.domain.repository

import com.coparently.app.domain.model.Event
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Repository interface for managing events.
 * Part of the domain layer in Clean Architecture.
 */
interface EventRepository {
    /**
     * Gets all events as a Flow.
     */
    fun getAllEvents(): Flow<List<Event>>

    /**
     * Gets events for a specific date range.
     */
    fun getEventsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>>

    /**
     * Gets events for a specific date.
     */
    fun getEventsByDate(date: LocalDateTime): Flow<List<Event>>

    /**
     * Gets an event by ID.
     */
    suspend fun getEventById(id: String): Event?

    /**
     * Gets events for a specific parent owner.
     */
    fun getEventsByParent(parentOwner: String): Flow<List<Event>>

    /**
     * Inserts a new event.
     */
    suspend fun insertEvent(event: Event)

    /**
     * Updates an existing event.
     */
    suspend fun updateEvent(event: Event)

    /**
     * Deletes an event.
     */
    suspend fun deleteEvent(event: Event)

    /**
     * Deletes an event by ID.
     */
    suspend fun deleteEventById(id: String)

    /**
     * Syncs events with Firestore.
     */
    /**
     * Uploads what is pending and pulls the remote side once, then **returns**.
     *
     * Named for the shape rather than for the subject (CQ-10). This was `syncWithFirestore()`
     * on all seven repositories, and on three of them it meant the opposite: an endless
     * snapshot listener that never returns. `SyncService.performFullSync()` already awaits the
     * pet one, so adding an expense call beside it by analogy — which is exactly what the old
     * name invited — would have made `performFullSync()` hang, `SyncWorker` be killed at
     * WorkManager's ten-minute ceiling, and sync stop entirely, with no exception and no log.
     */
    suspend fun pullOnce()
}

