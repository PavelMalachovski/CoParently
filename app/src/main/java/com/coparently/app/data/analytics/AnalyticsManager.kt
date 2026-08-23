package com.coparently.app.data.analytics

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.logEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for logging analytics events to Firebase Analytics.
 * Provides centralized analytics tracking for app events.
 *
 * @property analytics Firebase Analytics instance
 */
@Singleton
class AnalyticsManager @Inject constructor(
    private val analytics: FirebaseAnalytics
) {

    /**
     * Log screen view event.
     *
     * @param screenName Name of the screen viewed
     * @param screenClass Class name of the screen
     */
    fun logScreenView(screenName: String, screenClass: String = screenName) {
        analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            param(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            param(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
        }
    }

    /**
     * Log user login event.
     *
     * @param method Authentication method used (e.g., "google", "email")
     */
    fun logLogin(method: String) {
        analytics.logEvent(FirebaseAnalytics.Event.LOGIN) {
            param(FirebaseAnalytics.Param.METHOD, method)
        }
    }

    /**
     * Log user signup event.
     *
     * @param method Registration method used (e.g., "google", "email")
     */
    fun logSignUp(method: String) {
        analytics.logEvent(FirebaseAnalytics.Event.SIGN_UP) {
            param(FirebaseAnalytics.Param.METHOD, method)
        }
    }

    /**
     * Log pairing invitation sent event.
     *
     * @param method Method used to send invitation (e.g., "email")
     */
    fun logInvitationSent(method: String = "email") {
        analytics.logEvent("invitation_sent") {
            param("method", method)
        }
    }

    /**
     * Log pairing invitation accepted event.
     */
    fun logInvitationAccepted() {
        analytics.logEvent("invitation_accepted", null)
    }

    /**
     * Log child info added event.
     */
    fun logChildInfoAdded() {
        analytics.logEvent("child_info_added", null)
    }

    /**
     * Log child info updated event.
     */
    fun logChildInfoUpdated() {
        analytics.logEvent("child_info_updated", null)
    }

    /**
     * Log child info deleted event.
     */
    fun logChildInfoDeleted() {
        analytics.logEvent("child_info_deleted", null)
    }

    /**
     * Log pet added event.
     */
    fun logPetAdded() {
        analytics.logEvent("pet_added", null)
    }

    /**
     * Log pet updated event.
     */
    fun logPetUpdated() {
        analytics.logEvent("pet_updated", null)
    }

    /**
     * Log pet deleted event.
     */
    fun logPetDeleted() {
        analytics.logEvent("pet_deleted", null)
    }

    /**
     * Log event created.
     *
     * @param eventType Type of event created (e.g., "pickup", "appointment", "other")
     */
    fun logEventCreated(eventType: String) {
        analytics.logEvent("event_created") {
            param("event_type", eventType)
        }
    }

    /**
     * Log event updated.
     *
     * @param eventType Type of event updated
     */
    fun logEventUpdated(eventType: String) {
        analytics.logEvent("event_updated") {
            param("event_type", eventType)
        }
    }

    /**
     * Log event deleted.
     *
     * @param eventType Type of event deleted
     */
    fun logEventDeleted(eventType: String) {
        analytics.logEvent("event_deleted") {
            param("event_type", eventType)
        }
    }

    /**
     * Log Google Calendar sync event.
     *
     * @param success Whether sync was successful
     */
    fun logCalendarSync(success: Boolean) {
        analytics.logEvent("calendar_sync") {
            param("success", success.toString())
        }
    }

    /**
     * Log push notifications enabled/disabled.
     *
     * @param enabled Whether notifications are enabled
     */
    fun logNotificationsToggled(enabled: Boolean) {
        analytics.logEvent("notifications_toggled") {
            param("enabled", enabled.toString())
        }
    }

    /**
     * Log theme changed event.
     *
     * @param isDark Whether dark theme is enabled
     */
    fun logThemeChanged(isDark: Boolean) {
        analytics.logEvent("theme_changed") {
            param("dark_mode", isDark.toString())
        }
    }

    /**
     * Log calendar view mode changed.
     *
     * @param viewMode View mode selected (e.g., "day", "week", "month")
     */
    fun logCalendarViewChanged(viewMode: String) {
        analytics.logEvent("calendar_view_changed") {
            param("view_mode", viewMode)
        }
    }

    /**
     * Log search performed.
     *
     * @param searchTerm Search term used
     */
    fun logSearch(searchTerm: String) {
        analytics.logEvent(FirebaseAnalytics.Event.SEARCH) {
            param(FirebaseAnalytics.Param.SEARCH_TERM, searchTerm)
        }
    }

    /**
     * Set user ID for analytics.
     *
     * @param userId User ID to set
     */
    fun setUserId(userId: String?) {
        analytics.setUserId(userId)
    }

    /**
     * Set user property.
     *
     * @param name Property name
     * @param value Property value
     */
    fun setUserProperty(name: String, value: String?) {
        analytics.setUserProperty(name, value)
    }

    /**
     * Log custom event with parameters.
     *
     * @param eventName Name of the event
     * @param params Map of event parameters
     */
    fun logCustomEvent(eventName: String, params: Map<String, String> = emptyMap()) {
        analytics.logEvent(eventName) {
            params.forEach { (key, value) ->
                param(key, value)
            }
        }
    }
}

