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
    suspend fun syncWithFirestore()
}
