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
 * Remote data source for child information using Firestore.
 * Handles all Firestore operations for child info shared between parents.
 */
@Singleton
class FirestoreChildInfoDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val childInfoCollection = "child_info"

    // `getAllChildInfo` was removed by the August 2026 audit, for the reason given in
    // `FirestoreEventDataSource`: it queried the whole `child_info` collection with no
    // `sharedWith` filter — every family's children, medical notes included — and had no
    // caller. `getChildInfoForParent` below is the filtered read the app actually uses.

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
     * Marks a child record deleted, leaving the document in place so the co-parent can be told (CQ-19).
     *
     * `update` rather than `set`: the `child_info` read rule is keyed on `sharedWith`, which
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
    suspend fun tombstoneChildInfo(id: String, deletedAtMillis: Long, deletedBy: String): Result<Unit> {
        return try {
            firestore.collection(childInfoCollection)
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

