package com.coparently.app.domain.repository

import com.coparently.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing users.
 * Part of the domain layer in Clean Architecture.
 *
 * Over detekt's `TooManyFunctions` threshold by one, and deliberately so: the eleventh is
 * [observeCurrentUserId], the reactive counterpart of [getCurrentUserId]. Both are needed —
 * a suspending call site wants the snapshot, a ViewModel wants the stream, and offering
 * only the snapshot is what let `ChatViewModel` freeze a session identity in `init` and
 * leave the co-parent action dead. Splitting the interface to satisfy the threshold would
 * move that judgement into the type system for no benefit.
 */
@Suppress("TooManyFunctions")
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
     * The signed-in user's id (Firebase UID) as a stream: the value at subscription time,
     * and every later sign-in, sign-out and account switch. Emits null while signed out,
     * including the brief window on a cold start before Firebase Auth restores its session.
     *
     * The one-shot [getCurrentUserId] is a snapshot of the same thing and is fine for
     * a suspending call site. A ViewModel that captures it once in `init` is not: the
     * value it happens to read on construction then stands in for the whole session, and
     * anything gated on it stays dead for the lifetime of that ViewModel. That is exactly
     * how the chat entry point ended up doing nothing at all — see `ChatViewModel`.
     */
    fun observeCurrentUserId(): Flow<String?>

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

