package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote data source for users using Firestore.
 * Handles all Firestore operations for user profiles.
 */
@Singleton
class FirestoreUserDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val usersCollection = "users"
    private val invitationsCollection = "invitations"

    /**
     * Gets a user by Firebase UID.
     * Uses DEFAULT source which tries server first, falls back to cache automatically if offline.
     */
    suspend fun getUserById(uid: String): Map<String, Any?>? {
        // Validate uid before making request
        if (uid.isBlank()) {
            android.util.Log.w("FirestoreUserDataSource", "Attempted to get user with blank uid")
            return null
        }

        return try {
            // Use DEFAULT source - tries server first, automatically falls back to cache if offline
            val snapshot = firestore.collection(usersCollection)
                .document(uid)
                .get()
                .await()

            android.util.Log.d("FirestoreUserDataSource", "Got user from ${if (snapshot.metadata.isFromCache) "cache" else "server"}: $uid")
            snapshot.data
        } catch (e: Exception) {
            android.util.Log.e("FirestoreUserDataSource", "Failed to get user: $uid", e)
            null
        }
    }

    /**
     * Writes [userData] into the user profile, creating the document if it does not exist.
     *
     * Always a `set(..., merge)`: `users/{uid}` carries state this app is not the only
     * writer of — `partnerId` and `pairedAt` come from the pairing Cloud Function,
     * `pendingRevocationOf` from the unpair sweep — so a full `.set()` here would silently
     * delete whichever of them the caller's map happened to omit.
     */
    suspend fun updateUser(uid: String, userData: Map<String, Any?>): Result<Unit> {
        return try {
            // Use set with merge instead of update to create document if it doesn't exist
            firestore.collection(usersCollection)
                .document(uid)
                .set(userData, com.google.firebase.firestore.SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets a user by email.
     * Uses DEFAULT source which tries server first, falls back to cache automatically if offline.
     */
    suspend fun getUserByEmail(email: String): Map<String, Any?>? {
        return try {
            // Use DEFAULT source - tries server first, automatically falls back to cache if offline
            val snapshot = firestore.collection(usersCollection)
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()

            android.util.Log.d("FirestoreUserDataSource", "Got user by email from ${if (snapshot.metadata.isFromCache) "cache" else "server"}: $email")
            snapshot.documents.firstOrNull()?.data
        } catch (e: Exception) {
            android.util.Log.e("FirestoreUserDataSource", "Failed to get user by email: $email", e)
            null
        }
    }

    /**
     * Creates an invitation.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun createInvitation(
        invitationId: String,
        fromUserId: String,
        toEmail: String,
        invitationData: Map<String, Any?>
    ): Result<Unit> {
        // Parameters are already included in invitationData
        return try {
            firestore.collection(invitationsCollection)
                .document(invitationId)
                .set(invitationData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets invitation by ID.
     * Uses DEFAULT source which tries server first, falls back to cache automatically if offline.
     */
    suspend fun getInvitationById(invitationId: String): Map<String, Any?>? {
        return try {
            // Use DEFAULT source - tries server first, automatically falls back to cache if offline
            val snapshot = firestore.collection(invitationsCollection)
                .document(invitationId)
                .get()
                .await()

            android.util.Log.d("FirestoreUserDataSource", "Got invitation from ${if (snapshot.metadata.isFromCache) "cache" else "server"}: $invitationId")
            snapshot.data
        } catch (e: Exception) {
            android.util.Log.e("FirestoreUserDataSource", "Failed to get invitation: $invitationId", e)
            null
        }
    }

    /**
     * Gets invitations for a specific email.
     * Uses DEFAULT source which tries server first, falls back to cache automatically if offline.
     */
    suspend fun getInvitationsForEmail(email: String): List<Map<String, Any?>> {
        // Validate email before making request
        if (email.isBlank()) {
            android.util.Log.w("FirestoreUserDataSource", "Attempted to get invitations with blank email")
            return emptyList()
        }

        return try {
            // Use DEFAULT source - tries server first, automatically falls back to cache if offline
            val snapshot = firestore.collection(invitationsCollection)
                .whereEqualTo("toEmail", email)
                .whereEqualTo("status", "pending")
                .get()
                .await()

            android.util.Log.d("FirestoreUserDataSource", "Got invitations from ${if (snapshot.metadata.isFromCache) "cache" else "server"}: $email")
            snapshot.documents.map { it.data!! }
        } catch (e: Exception) {
            android.util.Log.e("FirestoreUserDataSource", "Failed to get invitations: $email", e)
            emptyList()
        }
    }

    /**
     * Updates invitation status.
     */
    suspend fun updateInvitationStatus(invitationId: String, status: String): Result<Unit> {
        return try {
            firestore.collection(invitationsCollection)
                .document(invitationId)
                .update("status", status)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes an invitation.
     */
    suspend fun deleteInvitation(invitationId: String): Result<Unit> {
        return try {
            firestore.collection(invitationsCollection)
                .document(invitationId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

