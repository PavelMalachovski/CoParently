package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing Firebase Cloud Messaging tokens and notifications.
 * Handles token registration, updates, and notification payload creation.
 */
@Singleton
class FcmService @Inject constructor(
    private val firebaseMessaging: FirebaseMessaging,
    private val firestore: FirebaseFirestore,
    private val firebaseAuthService: FirebaseAuthService
) {
    private val gson = Gson()

    /**
     * Gets the current FCM token.
     *
     * @return The FCM token or null if unavailable
     */
    suspend fun getCurrentToken(): String? {
        return try {
            firebaseMessaging.token.await()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Updates the FCM token for the current user in Firestore.
     *
     * @param token The FCM token to save
     */
    suspend fun updateUserToken(token: String): Result<Unit> {
        return try {
            val currentUser = firebaseAuthService.getCurrentUser() ?: return Result.failure(
                IllegalStateException("User not authenticated")
            )

            firestore.collection("users")
                .document(currentUser.uid)
                .update("fcmToken", token)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Subscribes the current user to a topic for receiving notifications.
     *
     * @param topic The topic to subscribe to
     */
    suspend fun subscribeToTopic(topic: String): Result<Unit> {
        return try {
            firebaseMessaging.subscribeToTopic(topic).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unsubscribes the current user from a topic.
     *
     * @param topic The topic to unsubscribe from
     */
    suspend fun unsubscribeFromTopic(topic: String): Result<Unit> {
        return try {
            firebaseMessaging.unsubscribeFromTopic(topic).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a notification payload for event creation/update.
     * Note: Actual sending should be done via Cloud Functions for security.
     *
     * @param eventId The event ID
     * @param eventTitle The event title
     * @param action The action performed (created, updated, deleted)
     * @param performedBy Name of the user who performed the action
     * @return Notification data map
     */
    fun createEventNotificationPayload(
        eventId: String,
        eventTitle: String,
        action: String,
        performedBy: String
    ): Map<String, String> {
        return mapOf(
            "type" to "event_$action",
            "eventId" to eventId,
            "title" to when (action) {
                "created" -> "New Event: $eventTitle"
                "updated" -> "Event Updated: $eventTitle"
                "deleted" -> "Event Deleted: $eventTitle"
                else -> "Event Notification"
            },
            "body" to "$performedBy ${action} an event",
            "timestamp" to System.currentTimeMillis().toString()
        )
    }

    /**
     * Creates a notification payload for invitation.
     *
     * @param invitationId The invitation ID
     * @param fromUserName Name of the user sending the invitation
     * @return Notification data map
     */
    fun createInvitationNotificationPayload(
        invitationId: String,
        fromUserName: String
    ): Map<String, String> {
        return mapOf(
            "type" to "invitation_received",
            "invitationId" to invitationId,
            "title" to "Co-Parent Invitation",
            "body" to "$fromUserName invited you to share a calendar",
            "timestamp" to System.currentTimeMillis().toString()
        )
    }

    /**
     * Creates a notification payload for child info update.
     *
     * @param childInfoId The child info ID
     * @param childName The child's name
     * @param updatedBy Name of the user who updated the info
     * @return Notification data map
     */
    fun createChildInfoNotificationPayload(
        childInfoId: String,
        childName: String,
        updatedBy: String
    ): Map<String, String> {
        return mapOf(
            "type" to "child_info_updated",
            "childInfoId" to childInfoId,
            "title" to "Child Info Updated",
            "body" to "$updatedBy updated information for $childName",
            "timestamp" to System.currentTimeMillis().toString()
        )
    }

    /**
     * Sends notification data to Firestore for Cloud Functions to process.
     * This creates a document in a notifications queue that triggers a Cloud Function.
     *
     * @param targetUserId The Firebase UID of the user to notify
     * @param notificationData The notification payload
     */
    suspend fun queueNotificationForUser(
        targetUserId: String,
        notificationData: Map<String, String>
    ): Result<Unit> {
        return try {
            val notificationDoc = mapOf(
                "targetUserId" to targetUserId,
                "data" to notificationData,
                "createdAt" to System.currentTimeMillis(),
                "status" to "pending"
            )

            firestore.collection("notification_queue")
                .add(notificationDoc)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets the partner's FCM token for direct notification.
     *
     * @param partnerId Firebase UID of the partner
     * @return The partner's FCM token or null
     */
    suspend fun getPartnerToken(partnerId: String): String? {
        return try {
            val userDoc = firestore.collection("users")
                .document(partnerId)
                .get()
                .await()

            userDoc.getString("fcmToken")
        } catch (e: Exception) {
            null
        }
    }
}
