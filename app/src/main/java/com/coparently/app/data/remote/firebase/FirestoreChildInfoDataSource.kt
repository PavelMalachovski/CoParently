package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote data source for child information using Firestore.
 * Handles all Firestore operations for child info shared between parents.
 */
@Singleton
class FirestoreChildInfoDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val childInfoCollection = "child_info"

    /**
     * Gets all child information as a Flow.
     */
    fun getAllChildInfo(): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(childInfoCollection)
            .orderBy("childName")
            .get()
            .await()
        emit(snapshot.documents.mapNotNull { it.data })
    }

    /**
     * Gets child information for a specific parent pair.
     * Parents can only see child info they're associated with.
     *
     * @param parentId Firebase UID of one of the parents
     */
    fun getChildInfoForParent(parentId: String): Flow<List<Map<String, Any?>>> = flow {
        val snapshot = firestore.collection(childInfoCollection)
            .whereArrayContains("sharedWith", parentId)
            .orderBy("childName")
            .get()
            .await()
        emit(snapshot.documents.mapNotNull { it.data })
    }

    /**
     * Gets child information by ID.
     *
     * @param id The child info ID
     * @return The child information data or null if not found
     */
    suspend fun getChildInfoById(id: String): Map<String, Any?>? {
        return firestore.collection(childInfoCollection)
            .document(id)
            .get()
            .await()
            .data
    }

    /**
     * Inserts or updates child information.
     *
     * @param id The child info ID
     * @param childInfoData The child information data
     */
    suspend fun upsertChildInfo(id: String, childInfoData: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(childInfoCollection)
                .document(id)
                .set(childInfoData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates specific fields of child information.
     *
     * @param id The child info ID
     * @param updates Map of field updates
     */
    suspend fun updateChildInfo(id: String, updates: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(childInfoCollection)
                .document(id)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes child information by ID.
     *
     * @param id The child info ID
     */
    suspend fun deleteChildInfo(id: String): Result<Unit> {
        return try {
            firestore.collection(childInfoCollection)
                .document(id)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Observes real-time changes to child information for specific parents.
     *
     * @param parentIds List of parent Firebase UIDs
     */
    fun observeChildInfoForParents(parentIds: List<String>): Flow<List<Map<String, Any?>>> = flow {
        // In Firestore, we need to query for documents where sharedWith array contains any of the parentIds
        // For simplicity, we'll query for the first parent and rely on sharedWith containing both
        if (parentIds.isNotEmpty()) {
            val snapshot = firestore.collection(childInfoCollection)
                .whereArrayContains("sharedWith", parentIds.first())
                .orderBy("childName")
                .get()
                .await()
            emit(snapshot.documents.mapNotNull { it.data })
        } else {
            emit(emptyList())
        }
    }
}

