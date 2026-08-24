package com.coparently.app.data.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for handling push notification setup and registration.
 * Coordinates FCM token management and notification permissions.
 */
@Singleton
class NotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fcmService: FcmService,
    private val firebaseAuthService: FirebaseAuthService,
    private val crashlyticsManager: CrashlyticsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Checks if notification permission is granted.
     * For Android 13+ (API 33+), checks POST_NOTIFICATIONS permission.
     * For older versions, always returns true.
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Initializes notifications by requesting FCM token and registering it.
     * Should be called when the app starts and user is authenticated.
     */
    fun initializeNotifications() {
        scope.launch {
            try {
                // Check if user is authenticated
                val currentUser = firebaseAuthService.getCurrentUser()
                if (currentUser == null) {
                    return@launch
                }

                // Check if we have notification permission
                if (!hasNotificationPermission()) {
                    return@launch
                }

                // Get and register FCM token
                val token = fcmService.getCurrentToken()
                if (token != null) {
                    fcmService.updateUserToken(token)
                }
            } catch (e: Exception) {
                // Push registration failing silently is how a parent stops receiving anything
                // without either of them learning why. It must not crash the app; it must not be
                // invisible either.
                android.util.Log.e(TAG, "FCM token registration failed", e)
                crashlyticsManager.recordException(e)
            }
        }
    }

    /**
     * Registers FCM token with the backend.
     * Should be called after permission is granted.
     */
    fun registerToken() {
        scope.launch {
            try {
                val token = fcmService.getCurrentToken()
                if (token != null) {
                    fcmService.updateUserToken(token)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "FCM token refresh failed", e)
                crashlyticsManager.recordException(e)
            }
        }
    }

    /**
     * Subscribes to co-parenting notifications topic.
     * Called when user pairs with a co-parent.
     */
    suspend fun subscribeToCoParentTopic(partnerId: String): Result<Unit> {
        return try {
            fcmService.subscribeToTopic("coparent_$partnerId")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unsubscribes from co-parenting notifications topic.
     * Called when user unpairs from a co-parent.
     */
    suspend fun unsubscribeFromCoParentTopic(partnerId: String): Result<Unit> {
        return try {
            fcmService.unsubscribeFromTopic("coparent_$partnerId")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "NotificationManager"
    }
}

