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
     * Reads every event shared with [uid] — the user's own events plus the ones their
     * co-parent shared with them.
     *
     * The filter is `array-contains sharedWith` and not `whereIn("createdByFirebaseUid", …)`
     * on purpose. Firestore validates a *query* against its potential result set rather than
     * against the documents it actually returns, so a query is only accepted when its
     * constraints structurally guarantee the read rule. The `events` read rule is
     * `createdByFirebaseUid == request.auth.uid || request.auth.uid in resource.data.sharedWith`;
     * `array-contains sharedWith == uid` implies the second branch for every possible result,
     * whereas `whereIn("createdByFirebaseUid", [uid, partnerUid])` implies neither branch for the
     * partner half of the union and would be rejected outright.
     *
     * It is also the correct *result set*: a paired parent must see what their co-parent shared
     * with them, which ownership-keyed filtering cannot express. Writers stamp both parents into
     * `sharedWith` (see `EventRepositoryImpl.toFirestoreMap` and `SyncService.syncEvents`).
     *
     * This previously filtered on `parentOwner`, which holds "mom"/"dad" rather than a UID, so it
     * could never match — and, being unconstrained on any field the rule authorizes, it was
     * rejected wholesale, aborting the rest of the sync.
     *
     * @param uid Firebase UID of the reader.
     */
    fun observeEventsSharedWith(uid: String): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(eventsCollection)
            .whereArrayContains("sharedWith", uid)
            .orderBy("startDateTime")
            .get()
            .await()
        emit(snapshot.documents.mapNotNull { it.data })
    }
}

