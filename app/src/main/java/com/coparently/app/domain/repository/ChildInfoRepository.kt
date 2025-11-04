package com.coparently.app.domain.repository

import com.coparently.app.domain.model.ChildInfo
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing child information.
 * Part of the domain layer in Clean Architecture.
 */
interface ChildInfoRepository {

    /**
     * Gets all child information as a Flow.
     *
     * @return Flow of list of all child information
     */
    fun getAllChildInfo(): Flow<List<ChildInfo>>

    /**
     * Gets child information by ID.
     *
     * @param id The child info ID
     * @return The child information or null if not found
     */
    suspend fun getChildInfoById(id: String): ChildInfo?

    /**
     * Gets child information by ID as a Flow.
     *
     * @param id The child info ID
     * @return Flow that emits the child information
     */
    fun observeChildInfoById(id: String): Flow<ChildInfo?>

    /**
     * Inserts or updates child information.
     *
     * @param childInfo The child information to insert or update
     */
    suspend fun upsertChildInfo(childInfo: ChildInfo)

    /**
     * Deletes child information.
     *
     * @param childInfo The child information to delete
     */
    suspend fun deleteChildInfo(childInfo: ChildInfo)

    /**
     * Syncs local child information with Firestore.
     */
    suspend fun syncWithFirestore()
}

