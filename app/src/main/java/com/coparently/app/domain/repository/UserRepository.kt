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
     * The signed-in user's id (Firebase UID), independent of whether a local
     * profile row exists yet. Null when signed out. Use this when only the id is
     * needed — [getCurrentUser] returns null until a local profile row is created
     * (which today only happens during pairing).
     */
    suspend fun getCurrentUserId(): String?

    /**
     * Makes sure the signed-in user has an identity-bearing profile, locally and in
     * Firestore, and keeps it current.
     *
     * `users/{uid}` is what the *other* parent reads to learn who they are paired with,
     * and what this app reads to title a conversation or an invitation. Firebase Auth
     * does not create that document, so without this call it only ever comes into
     * existence as a side effect of the FCM token write — carrying `fcmToken` and
     * nothing else, which renders as "Unknown"/"Email unavailable" on the co-parent's
     * pairing screen.
     *
     * Implementations must merge rather than overwrite (the document also carries
     * `partnerId`, `pairedAt`, `fcmToken` and `pendingRevocationOf`) and must never
     * replace a stored name with a weaker guess. Best-effort: failures are logged, not
     * thrown, because no user-facing action depends on this having succeeded.
     */
    suspend fun ensureProfile()

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

