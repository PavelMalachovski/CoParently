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
     */
    suspend fun getUserById(uid: String): Map<String, Any?>? {
        return firestore.collection(usersCollection)
            .document(uid)
            .get()
            .await()
            .data
    }

    /**
     * Inserts or updates a user profile.
     */
    suspend fun upsertUser(uid: String, userData: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(usersCollection)
                .document(uid)
                .set(userData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates a user profile.
     */
    suspend fun updateUser(uid: String, userData: Map<String, Any?>): Result<Unit> {
        return try {
            firestore.collection(usersCollection)
                .document(uid)
                .update(userData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets a user by email.
     */
    suspend fun getUserByEmail(email: String): Map<String, Any?>? {
        val snapshot = firestore.collection(usersCollection)
            .whereEqualTo("email", email)
            .limit(1)
            .get()
            .await()

        return snapshot.documents.firstOrNull()?.data
    }

    /**
     * Creates an invitation.
     */
    suspend fun createInvitation(
        invitationId: String,
        fromUserId: String,
        toEmail: String,
        invitationData: Map<String, Any?>
    ): Result<Unit> {
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
     */
    suspend fun getInvitationById(invitationId: String): Map<String, Any?>? {
        return firestore.collection(invitationsCollection)
            .document(invitationId)
            .get()
            .await()
            .data
    }

    /**
     * Gets invitations for a specific email.
     */
    suspend fun getInvitationsForEmail(email: String): List<Map<String, Any?>> {
        val snapshot = firestore.collection(invitationsCollection)
            .whereEqualTo("toEmail", email)
            .whereEqualTo("status", "pending")
            .get()
            .await()
        return snapshot.documents.map { it.data!! }
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

