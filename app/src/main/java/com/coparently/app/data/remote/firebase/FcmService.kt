package com.coparently.app.data.remote.firebase

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for Firebase Cloud Messaging (FCM).
 * Handles token management and notification operations.
 */
@Singleton
class FcmService @Inject constructor(
    private val firebaseMessaging: FirebaseMessaging
) {
    /**
     * Gets the current FCM token.
     *
     * @return Result containing the token or error
     */
    suspend fun getToken(): Result<String> {
        return try {
            val token = firebaseMessaging.token.await()
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Subscribes to a topic for group notifications.
     *
     * @param topic Topic name to subscribe to
     * @return Result indicating success or failure
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
     * Unsubscribes from a topic.
     *
     * @param topic Topic name to unsubscribe from
     * @return Result indicating success or failure
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
     * Deletes the FCM token.
     *
     * @return Result indicating success or failure
     */
    suspend fun deleteToken(): Result<Unit> {
        return try {
            firebaseMessaging.deleteToken().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

