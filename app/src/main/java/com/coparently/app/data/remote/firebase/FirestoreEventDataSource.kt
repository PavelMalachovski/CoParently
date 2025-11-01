package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote data source for events using Firestore.
 * Handles all Firestore operations for events.
 */
@Singleton
class FirestoreEventDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val eventsCollection = "events"

    /**
     * Gets all events as a Flow.
     */
    fun getAllEvents(): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(eventsCollection)
            .orderBy("startDateTime")
            .get()
            .await()
        emit(snapshot.documents.map { it.data!! })
    }

    /**
     * Gets events for a specific date range.
     */
    fun getEventsByDateRange(startDate: String, endDate: String): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(eventsCollection)
            .whereGreaterThanOrEqualTo("startDateTime", startDate)
            .whereLessThanOrEqualTo("startDateTime", endDate)
            .orderBy("startDateTime")
            .get()
            .await()
        emit(snapshot.documents.map { it.data!! })
    }

    /**
     * Gets events for a specific parent owner.
     */
    fun getEventsByParent(parentOwner: String): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(eventsCollection)
            .whereEqualTo("parentOwner", parentOwner)
            .orderBy("startDateTime")
            .get()
            .await()
        emit(snapshot.documents.map { it.data!! })
    }

    /**
     * Gets an event by ID.
     */
    suspend fun getEventById(id: String): Map<String, Any?>? {
        return firestore.collection(eventsCollection)
            .document(id)
            .get()
            .await()
            .data
    }

    /**
     * Inserts a new event.
     */
    suspend fun insertEvent(id: String, eventData: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(eventsCollection)
                .document(id)
                .set(eventData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates an existing event.
     */
    suspend fun updateEvent(id: String, eventData: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(eventsCollection)
                .document(id)
                .update(eventData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes an event by ID.
     */
    suspend fun deleteEvent(id: String): Result<Unit> {
        return try {
            firestore.collection(eventsCollection)
                .document(id)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Observes real-time changes to events for a specific parent pair.
     */
    fun observeEventsForParents(parentIds: List<String>): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(eventsCollection)
            .whereIn("parentOwner", parentIds)
            .orderBy("startDateTime")
            .get()
            .await()
        emit(snapshot.documents.map { it.data!! })
    }
}

