package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coparently.app.data.local.entity.ChildInfoEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing child information data in the local Room database.
 * Provides CRUD operations for [ChildInfoEntity].
 */
@Dao
interface ChildInfoDao {

    /**
     * Gets all child information as a Flow.
     * The Flow will emit new values whenever the data changes.
     *
     * @return Flow of list of all child information
     */
    @Query("SELECT * FROM child_info ORDER BY childName ASC")
    fun getAllChildInfo(): Flow<List<ChildInfoEntity>>

    /**
     * Gets child information by ID.
     *
     * @param id The child info ID
     * @return The child information or null if not found
     */
    @Query("SELECT * FROM child_info WHERE id = :id")
    suspend fun getChildInfoById(id: String): ChildInfoEntity?

    /**
     * Gets child information by ID as a Flow.
     *
     * @param id The child info ID
     * @return Flow that emits the child information
     */
    @Query("SELECT * FROM child_info WHERE id = :id")
    fun observeChildInfoById(id: String): Flow<ChildInfoEntity?>

    /**
     * Inserts a child information.
     * If a child info with the same ID already exists, it will be replaced.
     *
     * @param childInfo The child information to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChildInfo(childInfo: ChildInfoEntity)

    /**
     * Inserts multiple child information entries.
     *
     * @param childInfoList The list of child information to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllChildInfo(childInfoList: List<ChildInfoEntity>)

    /**
     * Updates a child information.
     *
     * @param childInfo The child information to update
     */
    @Update
    suspend fun updateChildInfo(childInfo: ChildInfoEntity)

    /**
     * Deletes a child information.
     *
     * @param childInfo The child information to delete
     */
    @Delete
    suspend fun deleteChildInfo(childInfo: ChildInfoEntity)

    /**
     * Deletes a child information by ID.
     *
     * @param id The ID of the child information to delete
     */
    @Query("DELETE FROM child_info WHERE id = :id")
    suspend fun deleteChildInfoById(id: String)

    /**
     * Deletes all child information.
     */
    @Query("DELETE FROM child_info")
    suspend fun deleteAllChildInfo()

    /**
     * Gets all child information that needs to be synced to Firestore.
     *
     * @return List of child information that has not been synced
     */
    @Query("SELECT * FROM child_info WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedChildInfo(): List<ChildInfoEntity>

    /**
     * Marks child information as synced to Firestore.
     *
     * @param id The ID of the child information to mark as synced
     */
    @Query("UPDATE child_info SET syncedToFirestore = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)
}

