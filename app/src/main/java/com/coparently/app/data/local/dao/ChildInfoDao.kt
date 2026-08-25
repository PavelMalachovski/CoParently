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
    @Query("SELECT * FROM child_info WHERE deletedAtMillis IS NULL ORDER BY childName ASC")
    fun getAllChildInfo(): Flow<List<ChildInfoEntity>>

    /**
     * Gets child information by ID, **including a pending tombstone**.
     *
     * The one read deliberately not filtered on `deletedAtMillis`, mirroring
     * [EventDao.getEventById]: its callers are the sync and delete paths, and for them "there is
     * no such row" and "there is a row this device has deleted" are opposite answers. The
     * downstream half must recognise a local tombstone rather than treat the id as unknown and
     * insert the remote document over it, which would resurrect the record the parent just
     * deleted. A caller answering a *user's* question filters at the repository boundary —
     * see `ChildInfoRepositoryImpl.getChildInfoById`.
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
    @Query("SELECT * FROM child_info WHERE id = :id AND deletedAtMillis IS NULL")
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
     * **Deliberately not filtered on `deletedAtMillis`.** This is the outbox, and a pending
     * tombstone is the half of it that used to have no path at all (CQ-19): the caller splits
     * the two apart and writes each deletion as a tombstone before uploading the live rows.
     * Excluding them here is the one change that would silently restore the original defect.
     *
     * @return List of child information that has not been synced, deletions included
     */
    @Query("SELECT * FROM child_info WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedChildInfo(): List<ChildInfoEntity>

    /**
     * Marks a child record deleted, leaving the row in place as an outbox entry.
     *
     * `deletedAtMillis IS NULL` in the WHERE clause makes a second delete a no-op rather than
     * re-dating the first one, which would push the sweep's ninety-day deadline out every time
     * a retry ran.
     *
     * @param id The record's id.
     * @param deletedAtMillis When it was deleted, epoch millis.
     * @return How many rows were marked — zero if it was already a tombstone.
     */
    @Query(
        "UPDATE child_info SET deletedAtMillis = :deletedAtMillis, syncedToFirestore = 0 " +
            "WHERE id = :id AND deletedAtMillis IS NULL"
    )
    suspend fun markDeleted(id: String, deletedAtMillis: Long): Int

    /**
     * Marks child information as synced to Firestore.
     *
     * @param id The ID of the child information to mark as synced
     */
    @Query("UPDATE child_info SET syncedToFirestore = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    /**
     * Re-queues this user's own child-info rows for upload, so their audience is recomputed.
     *
     * Mirrors `EventDao.markOwnEventsUnsynced`. Rows whose `createdByFirebaseUid` is null are
     * deliberately not matched: nothing distinguishes this user's un-stamped row from anybody
     * else's, and re-publishing a stranger's row under this user's audience would be worse than
     * leaving it alone.
     *
     * @param myUid Firebase UID of the signed-in user.
     * @return How many rows were re-queued.
     */
    @Query(
        "UPDATE child_info SET syncedToFirestore = 0 " +
            "WHERE createdByFirebaseUid = :myUid AND deletedAtMillis IS NULL"
    )
    suspend fun markOwnChildInfoUnsynced(myUid: String): Int

    /**
     * Stamps [familyId] on every child_info row that names no family yet.
     *
     * The backfill half of docs/DESIGN-multi-family.md M-2: rows written before the column
     * existed, and rows written before this parent paired, both read as null. A device knows of
     * exactly one co-parenting relationship, so an unstamped row can only belong to that one.
     *
     * Null rows only. A row that already names a family keeps it — re-deriving the stamp is what
     * would let a re-pairing silently move a record into a different household.
     *
     * @return How many rows were stamped.
     */
    @Query("UPDATE child_info SET familyId = :familyId WHERE familyId IS NULL")
    suspend fun stampFamilyId(familyId: String): Int
}

