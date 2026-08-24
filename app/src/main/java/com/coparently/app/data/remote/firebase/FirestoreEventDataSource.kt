package com.coparently.app.data.remote.firebase

import com.coparently.app.data.sync.Tombstone
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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

    // `getAllEvents`, `getEventsByDateRange` and `getEventsByParent` were removed by the
    // August 2026 audit. All three queried the `events` collection with no owner filter — the
    // first two with none at all, the third on `parentOwner`, which holds the slot identifier
    // `"mom"`/`"dad"` and not a uid — so each one asked Firestore for *every* family's events.
    //
    // None had a caller: `EventRepositoryImpl` reads Room, and the live remote reads go through
    // `observeEventsSharedWith` below, which filters on `sharedWith` exactly as the rule
    // requires. Today the rules reject an unfiltered query outright, so these returned
    // PERMISSION_DENIED rather than other people's data — but that is the only thing that
    // stopped them, and they sat here named like ordinary API waiting for a rule to be relaxed
    // or an admin path to be added. Deleted rather than filtered, because a filtered version
    // would just duplicate `observeEventsSharedWith`.

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
     * Deletes an event document outright.
     *
     * **Not the delete path a parent takes** — that is [tombstoneEvent]. This removes the
     * document with nothing left behind, so a co-parent who has not synced yet will never
     * learn the event is gone. It survives for the one caller that is *not* a deletion: an
     * event turned private has to leave Firestore entirely, and there is deliberately no
     * record of it there afterwards.
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
     * Marks an event deleted, leaving the document in place so the co-parent can be told.
     *
     * `update` rather than `set`: the `events` read rule is keyed on `createdByFirebaseUid` and
     * `sharedWith`, both of which live on the existing document, so a tombstone that replaced it
     * would be one the co-parent is not allowed to read — a deletion delivered to nobody, which
     * is the defect this exists to fix.
     *
     * A missing document is [Result.success], not a failure. `update` raises `NOT_FOUND` when
     * the document never landed (a create that failed and was swallowed, or an event that only
     * ever existed locally), and in that case the deletion has nothing to reach: there is no
     * remote copy for a co-parent to be holding. Reporting failure would keep the local
     * tombstone queued forever, retrying a write that cannot succeed.
     */
    suspend fun tombstoneEvent(id: String, deletedAtMillis: Long, deletedBy: String): Result<Unit> {
        return try {
            firestore.collection(eventsCollection)
                .document(id)
                .update(Tombstone.fields(deletedAtMillis, deletedBy))
                .await()
            Result.success(Unit)
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                Result.success(Unit)
            } else {
                Result.failure(e)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
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

