package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing co-parent pairing and invitations.
 * Handles the invitation flow between two parents.
 */
@Singleton
class CoParentPairingService @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val firestoreUserDataSource: FirestoreUserDataSource,
    private val firebaseMessaging: FirebaseMessaging
) {
    private val invitationsCollection = "invitations"

    /**
     * Sends an invitation to another parent by email.
     *
     * @param fromUserId Firebase UID of the user sending the invitation
     * @param fromUserEmail Email of the user sending the invitation
     * @param fromUserName Name of the user sending the invitation
     * @param toEmail Email of the user receiving the invitation
     * @return Result containing invitation ID on success or error on failure
     */
    suspend fun sendInvitation(
        fromUserId: String,
        fromUserEmail: String,
        fromUserName: String,
        toEmail: String
    ): Result<String> {
        return try {
            val invitationId = UUID.randomUUID().toString()
            val invitationData = mapOf(
                "id" to invitationId,
                "fromUserId" to fromUserId,
                "fromUserEmail" to fromUserEmail,
                "fromUserName" to fromUserName,
                "toEmail" to toEmail,
                "status" to "pending",
                "createdAt" to System.currentTimeMillis()
            )

            firestoreUserDataSource.createInvitation(invitationId, fromUserId, toEmail, invitationData).getOrThrow()
            Result.success(invitationId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Accepts an invitation and pairs the two parents.
     *
     * @param invitationId ID of the invitation to accept
     * @param acceptingUserId Firebase UID of the user accepting the invitation
     * @return Result indicating success or failure
     */
    suspend fun acceptInvitation(invitationId: String, acceptingUserId: String): Result<Unit> {
        return try {
            val invitation = firestoreUserDataSource.getInvitationById(invitationId)
                ?: return Result.failure(IllegalStateException("Invitation not found"))

            val fromUserId = invitation["fromUserId"] as? String
                ?: return Result.failure(IllegalStateException("Invalid invitation data"))

            // Update both users with partner IDs
            firestoreUserDataSource.updateUser(fromUserId, mapOf("partnerId" to acceptingUserId)).getOrThrow()
            firestoreUserDataSource.updateUser(acceptingUserId, mapOf("partnerId" to fromUserId)).getOrThrow()

            // Update invitation status
            firestoreUserDataSource.updateInvitationStatus(invitationId, "accepted").getOrThrow()

            // Send notification to the inviting user
            sendPairingNotification(fromUserId, acceptingUserId)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rejects an invitation.
     *
     * @param invitationId ID of the invitation to reject
     * @return Result indicating success or failure
     */
    suspend fun rejectInvitation(invitationId: String): Result<Unit> {
        return try {
            firestoreUserDataSource.updateInvitationStatus(invitationId, "rejected").getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets pending invitations for a user.
     *
     * @param email Email of the user
     * @return Result containing list of invitations or error
     */
    suspend fun getPendingInvitations(email: String): Result<List<Map<String, Any?>>> {
        return try {
            val invitations = firestoreUserDataSource.getInvitationsForEmail(email)
            Result.success(invitations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes the partnership (unpairs two parents).
     *
     * @param userId Firebase UID of the user
     * @param partnerId Firebase UID of the partner
     * @return Result indicating success or failure
     */
    suspend fun removePartnership(userId: String, partnerId: String): Result<Unit> {
        return try {
            firestoreUserDataSource.updateUser(userId, mapOf("partnerId" to null)).getOrThrow()
            firestoreUserDataSource.updateUser(partnerId, mapOf("partnerId" to null)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sends a pairing notification via FCM.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun sendPairingNotification(toUserId: String, acceptedByUserId: String) {
        // This would typically be handled by Cloud Functions
        // For now, we just log the action
        // In production, you'd use Cloud Functions to send the actual FCM message
        // Parameters are kept for future implementation
    }

    /**
     * Gets partner information by partner ID.
     *
     * @param partnerId Firebase UID of the partner
     * @return Partner user data or null
     */
    suspend fun getPartnerInfo(partnerId: String): Map<String, Any?>? {
        return firestoreUserDataSource.getUserById(partnerId)
    }
}

