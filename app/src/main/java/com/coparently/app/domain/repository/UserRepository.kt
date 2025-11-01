package com.coparently.app.domain.repository

import com.coparently.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing users.
 * Part of the domain layer in Clean Architecture.
 */
interface UserRepository {
    /**
     * Gets all users as a Flow.
     */
    fun getAllUsers(): Flow<List<User>>

    /**
     * Gets a user by ID.
     */
    suspend fun getUserById(id: String): User?

    /**
     * Gets a user by email.
     */
    suspend fun getUserByEmail(email: String): User?

    /**
     * Gets the current authenticated user.
     */
    suspend fun getCurrentUser(): User?

    /**
     * Inserts or updates a user.
     */
    suspend fun upsertUser(user: User)

    /**
     * Updates a user.
     */
    suspend fun updateUser(user: User)

    /**
     * Deletes a user by ID.
     */
    suspend fun deleteUser(id: String)

    /**
     * Syncs user data with Firestore.
     */
    suspend fun syncWithFirestore()

    /**
     * Updates the FCM token for the current user.
     */
    suspend fun updateFcmToken(token: String)
}

