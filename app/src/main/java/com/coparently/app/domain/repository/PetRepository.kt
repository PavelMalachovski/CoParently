package com.coparently.app.domain.repository

import com.coparently.app.domain.model.Pet
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing pets.
 * Part of the domain layer in Clean Architecture.
 */
interface PetRepository {

    /**
     * Gets all pets as a Flow.
     */
    fun getAllPets(): Flow<List<Pet>>

    /**
     * Gets a pet by ID.
     *
     * @param id The pet ID
     * @return The pet or null if not found
     */
    suspend fun getPetById(id: String): Pet?

    /**
     * Gets a pet by ID as a Flow.
     *
     * @param id The pet ID
     */
    fun observePetById(id: String): Flow<Pet?>

    /**
     * Inserts or updates a pet.
     */
    suspend fun upsertPet(pet: Pet)

    /**
     * Deletes a pet.
     */
    suspend fun deletePet(pet: Pet)

    /**
     * Syncs local pets with Firestore.
     */
    /**
     * Uploads what is pending and pulls the remote side once, then **returns**.
     *
     * Named for the shape rather than for the subject (CQ-10). This was `syncWithFirestore()`
     * on all seven repositories, and on three of them it meant the opposite: an endless
     * snapshot listener that never returns. `SyncService.performFullSync()` already awaits the
     * pet one, so adding an expense call beside it by analogy — which is exactly what the old
     * name invited — would have made `performFullSync()` hang, `SyncWorker` be killed at
     * WorkManager's ten-minute ceiling, and sync stop entirely, with no exception and no log.
     */
    suspend fun pullOnce()
}
