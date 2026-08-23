package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coparently.app.data.local.entity.PetEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing pet data in the local Room database.
 * Provides CRUD operations for [PetEntity].
 */
@Dao
interface PetDao {

    /**
     * Gets all pets as a Flow, ordered by name.
     */
    @Query("SELECT * FROM pets ORDER BY name ASC")
    fun getAllPets(): Flow<List<PetEntity>>

    /**
     * Gets a pet by ID.
     *
     * @param id The pet ID
     * @return The pet or null if not found
     */
    @Query("SELECT * FROM pets WHERE id = :id")
    suspend fun getPetById(id: String): PetEntity?

    /**
     * Gets a pet by ID as a Flow.
     *
     * @param id The pet ID
     */
    @Query("SELECT * FROM pets WHERE id = :id")
    fun observePetById(id: String): Flow<PetEntity?>

    /**
     * Inserts a pet, replacing any existing row with the same ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPet(pet: PetEntity)

    /**
     * Inserts multiple pets.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPets(pets: List<PetEntity>)

    /**
     * Updates a pet.
     */
    @Update
    suspend fun updatePet(pet: PetEntity)

    /**
     * Deletes a pet by ID.
     */
    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun deletePetById(id: String)

    /**
     * Gets all pets that need to be synced to Firestore.
     */
    @Query("SELECT * FROM pets WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedPets(): List<PetEntity>

    /**
     * Marks a pet as synced to Firestore.
     */
    @Query("UPDATE pets SET syncedToFirestore = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    /**
     * Re-queues this user's own pet rows for upload, so their audience is recomputed.
     *
     * Mirrors `ChildInfoDao.markOwnChildInfoUnsynced`. Rows whose `createdByFirebaseUid` is
     * null are deliberately not matched — nothing distinguishes this user's un-stamped row
     * from anybody else's.
     *
     * @param myUid Firebase UID of the signed-in user.
     * @return How many rows were re-queued.
     */
    @Query("UPDATE pets SET syncedToFirestore = 0 WHERE createdByFirebaseUid = :myUid")
    suspend fun markOwnPetsUnsynced(myUid: String): Int
}
