package com.coparently.app.data.remote.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.coparently.app.R
import com.coparently.app.domain.pairing.PairingUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for handling push notifications.
 * Extends FirebaseMessagingService to receive and process FCM messages.
 *
 * `functions/index.js`'s `sendNotification` sends both a `notification` block
 * and a `data` block on every push. [onMessageReceived] is only invoked with
 * both blocks visible while the app is in the **foreground**: FCM's Android
 * SDK treats the presence of `notification` as its cue to display the
 * message from the system tray by itself while the app is backgrounded or
 * killed, without ever calling into this class. So the only place a message
 * could be shown twice is the foreground path, and that is the only path
 * this class needs to de-duplicate.
 */
@AndroidEntryPoint
class CoPlanlyMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var fcmService: FcmService

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Handles a push message delivered while the app is in the foreground.
     *
     * Every push carries both blocks (see the class doc), so posting once per
     * block — as this method used to — showed the same message twice. The
     * `data` block is authoritative; `notification` is only a fallback for a
     * push that (unusually) omits `data.title`.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val type = data["type"] // e.g., "event_created", "pairing_accepted"
        val title = data["title"] ?: remoteMessage.notification?.title ?: getString(R.string.app_name)
        val body = data["body"] ?: remoteMessage.notification?.body.orEmpty()
        // Both blocks can genuinely be absent (a data-only push with no title/body
        // set, or a malformed queue entry) — skip rather than post an empty shell.
        if (title.isEmpty() && body.isEmpty()) return

        showNotification(title, body, type)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Save the new token to Firestore
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fcmService.updateUserToken(token)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Shows a notification, deep-linking pairing events into the pairing
     * screen and everything else into the app's launcher activity.
     *
     * Pairing notifications reuse [PAIRING_NOTIFICATION_ID] instead of a
     * timestamp-derived id: a `pairing_accepted` followed by a `pairing_removed`
     * (or vice versa) describes the *current* pairing state, not two separate
     * things worth reviewing together, so the second should replace the first
     * in the tray rather than stack next to it. Every other notification type
     * keeps a timestamp id so unrelated notifications keep accumulating.
     */
    private fun showNotification(title: String, body: String, type: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val isPairingEvent = type == TYPE_PAIRING_ACCEPTED || type == TYPE_PAIRING_REMOVED
        val intent = if (isPairingEvent) {
            Intent(Intent.ACTION_VIEW, Uri.parse(PAIRING_DEEP_LINK)).apply {
                setPackage(packageName)
            }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }

        // Distinct per notification type so a pairing-accepted notification's
        // tap target can never overwrite a differently-typed one's PendingIntent
        // (PendingIntent identity is request code + intent action/data/component).
        val requestCode = type?.hashCode() ?: 0
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            // android.R.drawable.ic_dialog_info is a framework placeholder and
            // renders as a grey blob in the status bar.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notificationId = if (isPairingEvent) PAIRING_NOTIFICATION_ID else System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
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
        private const val CHANNEL_NAME = "CoPlanly Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications for events and invitations"

        /** Queued by `acceptPairingInvitation` (`functions/index.js`) for the inviter. */
        private const val TYPE_PAIRING_ACCEPTED = "pairing_accepted"

        /** Queued by `unpairCoParent` (`functions/index.js`) for the ex-partner. */
        private const val TYPE_PAIRING_REMOVED = "pairing_removed"

        /**
         * Opens the pairing screen with no prefilled code — see
         * [MainActivity][com.coparently.app.presentation.MainActivity]'s
         * `readPairingCode`, which treats a code-less pairing link as "land on
         * the pairing screen with nothing pre-filled" rather than a no-op.
         */
        private val PAIRING_DEEP_LINK = "${PairingUri.SCHEME}://${PairingUri.HOST}"

        /**
         * Stable id shared by both pairing notification types so a newer one
         * replaces an older one in the tray instead of stacking (see
         * [showNotification]).
         */
        private const val PAIRING_NOTIFICATION_ID = 918_273
    }
}
