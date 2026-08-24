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

            // Use set with merge to create document if it doesn't exist
            firestore.collection("users")
                .document(currentUser.uid)
                .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
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
     * The payload for an event being created, updated or deleted.
     *
     * Facts, not sentences (SEC-3). This used to build `"New Event: $eventTitle"` and
     * `"$performedBy created an event"` here and hand them to the other phone to render
     * verbatim — English regardless of the reader's language, and forgeable, because nothing
     * between the two devices decided what a notification was allowed to say.
     * [CoPlanlyMessagingService] now writes the sentence from its own string resources.
     *
     * @param eventId The event, for the deep link
     * @param eventTitle The event's title, as the co-parent typed it
     * @param action `created`, `updated` or `deleted`
     * @param performedBy Display name of the parent who did it
     * @return The payload, or null when [action] is one nothing announces — see
     *   [PushPayload.eventType].
     */
    fun createEventNotificationPayload(
        eventId: String,
        eventTitle: String,
        action: String,
        performedBy: String
    ): Map<String, String>? {
        val type = PushPayload.eventType(action) ?: return null
        return mapOf(
            PushPayload.TYPE to type,
            PushPayload.EVENT_ID to eventId,
            PushPayload.SUBJECT to eventTitle,
            PushPayload.ACTOR to performedBy
        )
    }

    /**
     * The payload for a child's information being updated. Facts, not sentences — see
     * [createEventNotificationPayload].
     *
     * @param childInfoId The record, for the deep link
     * @param childName The child's name
     * @param updatedBy Display name of the parent who made the change
     */
    fun createChildInfoNotificationPayload(
        childInfoId: String,
        childName: String,
        updatedBy: String
    ): Map<String, String> = mapOf(
        PushPayload.TYPE to PushPayload.CHILD_INFO_UPDATED,
        PushPayload.CHILD_INFO_ID to childInfoId,
        PushPayload.SUBJECT to childName,
        PushPayload.ACTOR to updatedBy
    )

    // `createInvitationNotificationPayload` was deleted with SEC-3: it had no caller. An
    // invitation being accepted is announced by `acceptPairingInvitation` in
    // `functions/index.js`, which is where it has to be — the accepting device is not the one
    // that needs telling.

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
