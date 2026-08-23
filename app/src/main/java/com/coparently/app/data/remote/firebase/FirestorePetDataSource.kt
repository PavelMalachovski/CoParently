package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
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
     * Deletes a pet by ID.
     *
     * @param id The pet ID
     */
    suspend fun deletePet(id: String): Result<Unit> {
        return try {
            firestore.collection(petsCollection)
                .document(id)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
