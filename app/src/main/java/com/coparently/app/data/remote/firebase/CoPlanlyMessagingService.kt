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
import com.coparently.app.domain.chat.ChatUri
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
 * `functions/index.js`'s `sendNotification` sends a **data-only** message —
 * there is no top-level `notification` block. A message that has one is
 * auto-displayed by the OS from the system tray whenever the app is
 * backgrounded or killed, and [onMessageReceived] is never called for it in
 * that case, so this class's deep links, icon and per-type notification id
 * would only ever run while the app happened to be in the foreground. A
 * data-only message with `android.priority: "high"` is instead delivered to
 * [onMessageReceived] uniformly across foreground, background and killed
 * app states, so this class is always the one deciding how — and whether —
 * to show it. The one state neither message shape reaches is a
 * user-force-stopped app: the OS blocks FCM delivery there regardless, and a
 * `notification`-block message would have still surfaced from the tray in
 * that case where a data-only one will not.
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
     * Handles a push message, delivered here in every app state (see the
     * class doc) because the backend sends data-only messages.
     *
     * `data["title"]`/`data["body"]` are authoritative — every producer in
     * `functions/index.js` sets them, and the backend no longer sends a
     * `notification` block for them to be missing from. `remoteMessage.notification`
     * is kept purely as a defensive fallback for a message that didn't come
     * from our backend (e.g. a test push sent from the Firebase console,
     * which defaults to a `notification` block).
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val type = data["type"] // e.g., "event_created", "pairing_accepted"
        val title = data["title"] ?: remoteMessage.notification?.title
        val body = data["body"] ?: remoteMessage.notification?.body
        // A malformed queue entry or an empty manual test push has neither -
        // skip rather than post a title-only or fully blank-looking notification.
        if (title.isNullOrEmpty() && body.isNullOrEmpty()) return

        // Only meaningful for TYPE_CHAT_MESSAGE (see notifyOfChatMessage in
        // functions/index.js, which is the only producer that sets it); null for every
        // other type, and showNotification only reads it for that one branch.
        val conversationId = data["conversationId"]

        showNotification(title ?: getString(R.string.app_name), body.orEmpty(), type, conversationId)
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
     * screen, a chat message into its conversation (or the Chat tab's list
     * when [conversationId] is null — a manual test push or an older payload),
     * and everything else into the app's launcher activity.
     *
     * Pairing notifications reuse [PAIRING_NOTIFICATION_ID] instead of a
     * timestamp-derived id: a `pairing_accepted` followed by a `pairing_removed`
     * (or vice versa) describes the *current* pairing state, not two separate
     * things worth reviewing together, so the second should replace the first
     * in the tray rather than stack next to it. A chat message reuses
     * [CHAT_NOTIFICATION_ID] for the same reason — the latest preview is what
     * matters, and the thread itself holds the full history — and, being a
     * distinct constant, never collides with (or is replaced by) a pairing
     * notification. Every other notification type keeps a timestamp id so
     * unrelated notifications keep accumulating.
     *
     * @param conversationId The `data["conversationId"]` [onMessageReceived] read off the
     *   message; only meaningful (and only ever non-null) for [TYPE_CHAT_MESSAGE].
     */
    private fun showNotification(title: String, body: String, type: String?, conversationId: String? = null) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val isPairingEvent = type == TYPE_PAIRING_ACCEPTED || type == TYPE_PAIRING_REMOVED
        val isChatMessage = type == TYPE_CHAT_MESSAGE
        val intent = when {
            isPairingEvent -> Intent(Intent.ACTION_VIEW, Uri.parse(PAIRING_DEEP_LINK)).apply {
                setPackage(packageName)
            }
            isChatMessage -> Intent(Intent.ACTION_VIEW, Uri.parse(ChatUri.build(conversationId))).apply {
                setPackage(packageName)
            }
            else -> packageManager.getLaunchIntentForPackage(packageName)
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

        val notificationId = when {
            isPairingEvent -> PAIRING_NOTIFICATION_ID
            isChatMessage -> CHAT_NOTIFICATION_ID
            else -> System.currentTimeMillis().toInt()
        }
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

        /** Queued by `onChatMessageCreated` (`functions/index.js`) for the message recipient. */
        private const val TYPE_CHAT_MESSAGE = "chat_message"

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

        /**
         * Stable id for a chat-message notification, distinct from
         * [PAIRING_NOTIFICATION_ID] so neither type ever replaces the other in
         * the tray (see [showNotification]).
         */
        private const val CHAT_NOTIFICATION_ID = 918_274
    }
}
