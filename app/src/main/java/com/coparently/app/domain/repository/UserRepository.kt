package com.coparently.app.domain.repository

import com.coparently.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing users.
 * Part of the domain layer in Clean Architecture.
 *
 * Over detekt's `TooManyFunctions` threshold, and deliberately so. The eleventh is
 * [observeCurrentUserId], the reactive counterpart of [getCurrentUserId]. Both are needed —
 * a suspending call site wants the snapshot, a ViewModel wants the stream, and offering
 * only the snapshot is what let `ChatViewModel` freeze a session identity in `init` and
 * leave the co-parent action dead. The twelfth is [getRemoteUserProfile]: Room holds a
 * `users` row for the signed-in user only (see that function's doc), so answering "what does
 * the co-parent's profile say" needs a distinct, Firestore-backed read rather than a variant
 * of [getUserById]. Splitting the interface to satisfy the threshold would move that
 * judgement into the type system for no benefit.
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
     * Observes a user by ID, re-emitting whenever the row changes.
     *
     * Emits null while no row exists yet: the profile row is written by [ensureProfile]
     * shortly after sign-in, so a fresh session legitimately observes nothing for a moment.
     * A one-shot [getUserById] read taken inside that window returns null and never
     * refreshes — this is what [ProfileViewModel][com.coparently.app.presentation.profile.ProfileViewModel]
     * waits on instead, so a screen opened during the race still receives the row once
     * [ensureProfile] finishes writing it.
     */
    fun observeUserById(id: String): Flow<User?>

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

    /**
     * Reads a profile straight from that user's `users/{uid}` Firestore document, bypassing
     * Room entirely.
     *
     * This exists for exactly one case: showing the **co-parent's** profile. Room stores a
     * `users` row for the signed-in user only — nothing writes one for the other parent — so
     * [getUserById] can never answer "what does the co-parent's record say", the same gap
     * `ParentsSource`'s class doc records for [PartnerSummary][com.coparently.app.domain.model.PartnerSummary].
     * The co-parent's slot-limited summary already exists there; this is the fuller read a
     * profile screen needs (birth date, phone, allergies, medical profile) without hanging
     * those fields on `PartnerSummary`, which every screen that only wants a name also loads.
     *
     * `firestore.rules` allows any paired partner to `get` (not list) the other's document —
     * pinned in `firestore-tests/rules/users-profile.test.js` — so this is a permitted read,
     * not a workaround.
     *
     * @param uid The other user's Firebase UID.
     * @return The profile, or null when the document does not exist or the read fails.
     */
    suspend fun getRemoteUserProfile(uid: String): User?
}

