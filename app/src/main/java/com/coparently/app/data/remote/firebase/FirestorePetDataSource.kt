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
 * Remote data source for pets using Firestore.
 *
 * The `pets` collection is gated on a `sharedWith` array in `firestore.rules`, so every
 * list query here carries the matching `whereArrayContains` filter — an unfiltered
 * collection query would be rejected outright (see CLAUDE.md item 12). Results are
 * deliberately **not** ordered server-side: `array-contains` plus `orderBy` on another
 * field needs a composite index, and Room orders the list for display anyway.
 */
@Singleton
class FirestorePetDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val petsCollection = "pets"

    /**
     * Gets the pets shared with one parent.
     *
     * @param parentId Firebase UID of the parent
     */
    fun getPetsForParent(parentId: String): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(petsCollection)
            .whereArrayContains("sharedWith", parentId)
            .get()
            .await()
        emit(snapshot.documents.mapNotNull { it.data })
    }

    /**
     * Gets a pet by ID.
     *
     * @param id The pet ID
     * @return The pet data or null if not found
     */
    suspend fun getPetById(id: String): Map<String, Any?>? {
        return firestore.collection(petsCollection)
            .document(id)
            .get()
            .await()
            .data
    }

    /**
     * Inserts or updates a pet document.
     *
     * @param id The pet ID
     * @param petData The pet data
     */
    suspend fun upsertPet(id: String, petData: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(petsCollection)
                .document(id)
                .set(petData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates specific fields of a pet document.
     *
     * @param id The pet ID
     * @param updates Map of field updates
     */
    suspend fun updatePet(id: String, updates: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(petsCollection)
                .document(id)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Marks a pet deleted, leaving the document in place so the co-parent can be told (CQ-19).
     *
     * `update` rather than `set`: the `pets` read rule is keyed on `sharedWith`, which
     * lives on the existing document, so a tombstone that replaced it would be one the co-parent
     * is not allowed to read — a deletion delivered to nobody, which is the defect this exists
     * to fix.
     *
     * A missing document is [Result.success], not a failure. `update` raises `NOT_FOUND` when
     * the document never landed, and in that case the deletion has nothing to reach: there is no
     * remote copy for a co-parent to be holding. Reporting failure would keep the local
     * tombstone queued forever, retrying a write that cannot succeed.
     *
     * @param id The record's id.
     * @param deletedAtMillis When it was deleted, epoch millis.
     * @param deletedBy Firebase UID of whoever deleted it.
     */
    suspend fun tombstonePet(id: String, deletedAtMillis: Long, deletedBy: String): Result<Unit> {
        return try {
            firestore.collection(petsCollection)
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
}
