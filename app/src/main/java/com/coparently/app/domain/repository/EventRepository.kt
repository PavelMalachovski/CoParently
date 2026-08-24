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
     * Fetches one event straight from the server and stores it, for a caller that needs an
     * event this device has not synced yet.
     *
     * The change-request inbox is that caller. A proposal arrives over the realtime
     * `change_requests` listener within seconds, but the event it is about is downloaded only by
     * the periodic sync — so "accept" could fail with "the event for this request no longer
     * exists" for a quarter of an hour after the co-parent sent it.
     *
     * @param id Document id of the event.
     * @return The event, now also in Room, or null when it is not readable — gone, tombstoned,
     *   or not shared with this user.
     */
    suspend fun fetchRemoteEvent(id: String): Event?

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

