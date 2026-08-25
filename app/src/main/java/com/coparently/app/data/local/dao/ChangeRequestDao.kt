package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coparently.app.data.local.entity.ChangeRequestEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for event change requests.
 */
@Dao
interface ChangeRequestDao {

    /**
     * All change requests, newest first.
     */
    @Query("SELECT * FROM change_requests ORDER BY createdAt DESC")
    fun getAllChangeRequests(): Flow<List<ChangeRequestEntity>>

    /**
     * Number of pending requests addressed to [userId] — drives the inbox badge.
     */
    @Query("SELECT COUNT(*) FROM change_requests WHERE status = 'PENDING' AND requestedTo = :userId")
    fun getPendingIncomingCount(userId: String): Flow<Int>

    /**
     * Change requests attached to a specific event, newest first.
     */
    @Query("SELECT * FROM change_requests WHERE eventId = :eventId ORDER BY createdAt DESC")
    fun getChangeRequestsForEvent(eventId: String): Flow<List<ChangeRequestEntity>>

    @Query("SELECT * FROM change_requests WHERE id = :id")
    suspend fun getChangeRequestById(id: String): ChangeRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChangeRequest(changeRequest: ChangeRequestEntity)

    @Query("DELETE FROM change_requests WHERE id = :id")
    suspend fun deleteChangeRequest(id: String)

    /**
     * Requests written locally whose Firestore write never landed.
     *
     * The outbox `syncedToFirestore` was always written for and nothing ever read: a request
     * created offline, or one whose write was refused, stayed on the sender's phone with no
     * path off it and no sign on screen that anything was wrong.
     */
    @Query("SELECT * FROM change_requests WHERE syncedToFirestore = 0 ORDER BY createdAt ASC")
    suspend fun getUnsyncedChangeRequests(): List<ChangeRequestEntity>
}
