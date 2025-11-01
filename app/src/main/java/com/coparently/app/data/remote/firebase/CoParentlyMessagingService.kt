package com.coparently.app.data.remote.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for handling push notifications.
 * Extends FirebaseMessagingService to receive and process FCM messages.
 */
@AndroidEntryPoint
class CoParentlyMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var fcmService: FcmService

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Handle data payload
        remoteMessage.data.let { data ->
            val title = data["title"] ?: "CoParently"
            val body = data["body"] ?: "You have a new notification"
            val type = data["type"] // e.g., "event_created", "invitation_received"

            showNotification(title, body, type)
        }

        // Handle notification payload
        remoteMessage.notification?.let { notification ->
            showNotification(
                notification.title ?: "CoParently",
                notification.body ?: "You have a new notification",
                null
            )
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // The token needs to be saved to Firestore
        // This should be handled by a repository or use case
    }

    /**
     * Shows a notification to the user.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun showNotification(title: String, body: String, type: String?) {
        // type parameter reserved for future use
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    /**
     * Creates a notification channel for Android O and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "coparently_notifications"
        private const val CHANNEL_NAME = "CoParently Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications for events and invitations"
    }
}

