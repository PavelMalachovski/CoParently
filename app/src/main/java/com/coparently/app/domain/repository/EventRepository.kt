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
    suspend fun syncWithFirestore()
}

