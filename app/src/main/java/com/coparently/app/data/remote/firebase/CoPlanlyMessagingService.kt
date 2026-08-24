package com.coparently.app.data.remote.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.coparently.app.R
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.sync.SyncWorker
import com.coparently.app.domain.chat.ChatUri
import com.coparently.app.domain.pairing.PairingUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    @Inject
    lateinit var crashlyticsManager: CrashlyticsManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Handles a push message, delivered here in every app state (see the
     * class doc) because the backend sends data-only messages.
     *
     * **This device writes the sentence** (SEC-3). The payload carries a
     * [PushPayload.TYPE] and the few names that type needs; the frame around them comes from
     * this app's own string resources, in the reader's language. It used to carry `title` and
     * `body` written by the *sending* phone and render them verbatim, which made a push able to
     * claim to be anything and made every notification English regardless of who read it.
     *
     * A type this build cannot compose is **dropped**, not rendered from whatever text happened
     * to arrive. That is the half that makes the change worth anything: a fallback to relayed
     * text would leave the forgery open under any unrecognised type. `firestore.rules` refuses a
     * client-written payload carrying `title`/`body` at all, so nothing legitimate is lost.
     *
     * `remoteMessage.notification` is no longer consulted either. It only ever appeared on a
     * message that did not come from this backend — a test push from the Firebase console — and
     * that is precisely the message whose text should not be trusted.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val type = data[PushPayload.TYPE]

        // Pull whatever the push is about *before* deciding whether it is renderable. A push
        // announced a change the device could not yet see: events, child records and pets are
        // downloaded only by the fifteen-minute worker, so tapping the notification opened an
        // app that still knew nothing about the thing it had just announced — and accepting a
        // proposed change failed outright, because the event was not in Room. Running the sync
        // even for a type this build has no wording for is deliberate: an unrecognised push is
        // still evidence that something changed on the server.
        SyncWorker.syncNow(applicationContext)

        val text = compose(type, data) ?: return

        // Only meaningful for TYPE_CHAT_MESSAGE (see notifyOfChatMessage in
        // functions/index.js, which is the only producer that sets it); null for every
        // other type, and showNotification only reads it for that one branch.
        val conversationId = data[PushPayload.CONVERSATION_ID]

        showNotification(text.title, text.body, type, conversationId)
    }

    /**
     * The notification's text, written here from [type] and the payload's names.
     *
     * Returns null when [type] is one this build has no wording for — an older notification
     * still in the queue, a newer client's type, or a forgery. Dropping it silently is the
     * intended outcome: there is nothing this device could show that it would be honest about.
     *
     * A missing or blank actor name falls back to a translated "your co-parent" rather than to
     * an empty gap in the sentence. `displayName` is genuinely absent for an email/password
     * account that never set one, so this is the normal case for some pairs, not a defect.
     */
    private fun compose(type: String?, data: Map<String, String>): PushText? {
        // The chat preview is the one notification whose text is not a frame: the title is who
        // sent it and the body is what they wrote. Both were composed server-side by
        // `notifyOfChatMessage`, which is the only party that has seen the message, and
        // `firestore.rules` refuses `chat_message` from a client — so this relays rather than
        // composes, without reopening what the rest of this function closes.
        if (type == TYPE_CHAT_MESSAGE) {
            val sender = data[PushPayload.ACTOR]?.takeIf { it.isNotBlank() }
                ?: getString(R.string.app_name)
            return PushText(sender, data[PushPayload.PREVIEW].orEmpty())
        }

        // `type?.let` rather than `PUSH_TEXT[type]`: the map is keyed on a non-null String, so
        // indexing it with the nullable value straight off the payload does not compile.
        val spec = type?.let { PUSH_TEXT[it] } ?: return null
        val actor = data[PushPayload.ACTOR]?.takeIf { it.isNotBlank() }
            ?: getString(R.string.push_actor_fallback)
        val body = when (spec.args) {
            BodyArgs.ACTOR_AND_SUBJECT -> getString(spec.body, actor, data[PushPayload.SUBJECT].orEmpty())
            BodyArgs.ACTOR -> getString(spec.body, actor)
            BodyArgs.DATE -> getString(spec.body, data[PushPayload.DATE].orEmpty())
            BodyArgs.DAY_COUNT -> {
                // An unparseable count composes nothing rather than announcing "0 days" — the
                // same rule as an unrecognised type. Only this build's own writer produces it.
                val count = data[PushPayload.DAY_COUNT]?.toIntOrNull() ?: return null
                resources.getQuantityString(spec.body, count, count)
            }
            BodyArgs.NONE -> getString(spec.body)
        }
        return PushText(getString(spec.title), body)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Save the new token to Firestore
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fcmService.updateUserToken(token)
            } catch (e: Exception) {
                // A token that never reaches Firestore means the co-parent's pushes go nowhere,
                // and nothing on either device says so.
                android.util.Log.e("CoPlanlyMessaging", "Storing the refreshed FCM token failed", e)
                crashlyticsManager.recordException(e)
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

    /** A composed notification, ready to render. */
    private data class PushText(val title: String, val body: String)

    /** Which of the payload's names a body string takes, in order. */
    private enum class BodyArgs { ACTOR_AND_SUBJECT, ACTOR, DATE, DAY_COUNT, NONE }

    /**
     * A type's wording: the frame, and what fills it.
     *
     * [body] is a plurals resource, not a string, when [args] is [BodyArgs.DAY_COUNT] — Czech,
     * Russian and Ukrainian each need three forms for "N days", so a count cannot go through
     * `getString`. Kotlin cannot express that in the type, hence this note.
     */
    private data class PushTextSpec(
        @StringRes val title: Int,
        val body: Int,
        val args: BodyArgs
    )

    companion object {
        private const val CHANNEL_ID = "coparently_notifications"
        private const val CHANNEL_NAME = "CoPlanly Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications for events and invitations"

        // The three server-only types, aliased from `PushPayload` rather than re-declared.
        // They were literals here and in the sending code, in the rules and in
        // `functions/index.js`; one of those four drifting is a notification that silently
        // stops being recognised, which looks exactly like a push that was never sent.

        /** Queued by `acceptPairingInvitation` (`functions/index.js`) for the inviter. */
        private const val TYPE_PAIRING_ACCEPTED = PushPayload.PAIRING_ACCEPTED

        /** Queued by `unpairCoParent` (`functions/index.js`) for the ex-partner. */
        private const val TYPE_PAIRING_REMOVED = PushPayload.PAIRING_REMOVED

        /** Queued by `onChatMessageCreated` (`functions/index.js`) for the message recipient. */
        private const val TYPE_CHAT_MESSAGE = PushPayload.CHAT_MESSAGE

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

        /**
         * Every notification this build knows how to word.
         *
         * A table rather than a `when`, so the set of types the app will render is one list a
         * reader can check against `PushPayload` and against `firestore.rules` — the three have
         * to agree, and two of them being code paths spread over a file would make that hard to
         * see. A type absent from here is dropped on arrival (see [compose]), which is also why
         * adding a type to `PushPayload` without adding it here is a push that silently never
         * appears.
         *
         * `chat_message` is deliberately not in it: its text is not a frame, and [compose]
         * handles it before consulting this.
         */
        private val PUSH_TEXT: Map<String, PushTextSpec> = mapOf(
            PushPayload.EVENT_CREATED to PushTextSpec(
                R.string.push_event_created_title,
                R.string.push_event_created_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.EVENT_UPDATED to PushTextSpec(
                R.string.push_event_updated_title,
                R.string.push_event_updated_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.EVENT_DELETED to PushTextSpec(
                R.string.push_event_deleted_title,
                R.string.push_event_deleted_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.CHILD_INFO_UPDATED to PushTextSpec(
                R.string.push_child_info_updated_title,
                R.string.push_child_info_updated_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.CHANGE_REQUEST_CREATED to PushTextSpec(
                R.string.push_change_request_created_title,
                R.string.push_change_request_created_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.CHANGE_REQUEST_ACCEPTED to PushTextSpec(
                R.string.push_change_request_accepted_title,
                R.string.push_change_request_accepted_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.CHANGE_REQUEST_DECLINED to PushTextSpec(
                R.string.push_change_request_declined_title,
                R.string.push_change_request_declined_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.CHANGE_REQUEST_CANCELLED to PushTextSpec(
                R.string.push_change_request_cancelled_title,
                R.string.push_change_request_cancelled_body,
                BodyArgs.ACTOR_AND_SUBJECT
            ),
            PushPayload.CUSTODY_PROPOSAL_PROPOSED to PushTextSpec(
                R.string.push_custody_proposal_proposed_title,
                R.string.push_custody_proposal_proposed_body,
                BodyArgs.NONE
            ),
            PushPayload.CUSTODY_PROPOSAL_ACCEPTED to PushTextSpec(
                R.string.push_custody_proposal_accepted_title,
                R.string.push_custody_proposal_accepted_body,
                BodyArgs.NONE
            ),
            PushPayload.CUSTODY_PROPOSAL_DECLINED to PushTextSpec(
                R.string.push_custody_proposal_declined_title,
                R.string.push_custody_proposal_declined_body,
                BodyArgs.NONE
            ),
            PushPayload.DAY_SWAP_OFFERED to PushTextSpec(
                R.string.push_day_swap_offered_title,
                R.string.push_day_swap_offered_body,
                BodyArgs.DATE
            ),
            PushPayload.DAY_SWAP_ACCEPTED to PushTextSpec(
                R.string.push_day_swap_accepted_title,
                R.string.push_day_swap_accepted_body,
                BodyArgs.DATE
            ),
            PushPayload.DAY_SWAP_DECLINED to PushTextSpec(
                R.string.push_day_swap_declined_title,
                R.string.push_day_swap_declined_body,
                BodyArgs.DATE
            ),
            PushPayload.DAY_SWAP_GROUP_OFFERED to PushTextSpec(
                R.string.push_day_swap_offered_title,
                R.plurals.push_day_swap_group_offered_body,
                BodyArgs.DAY_COUNT
            ),
            PushPayload.DAY_SWAP_GROUP_ACCEPTED to PushTextSpec(
                R.string.push_day_swap_accepted_title,
                R.plurals.push_day_swap_group_accepted_body,
                BodyArgs.DAY_COUNT
            ),
            PushPayload.DAY_SWAP_GROUP_DECLINED to PushTextSpec(
                R.string.push_day_swap_declined_title,
                R.plurals.push_day_swap_group_declined_body,
                BodyArgs.DAY_COUNT
            ),
            PushPayload.PAIRING_ACCEPTED to PushTextSpec(
                R.string.push_pairing_accepted_title,
                R.string.push_pairing_accepted_body,
                BodyArgs.ACTOR
            ),
            PushPayload.PAIRING_REMOVED to PushTextSpec(
                R.string.push_pairing_removed_title,
                R.string.push_pairing_removed_body,
                BodyArgs.ACTOR
            )
        )
    }
}
