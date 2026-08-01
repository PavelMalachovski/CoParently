package com.coparently.app.domain.feature

import android.util.Log
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.model.CalendarVariant
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages feature flags and A/B testing using Firebase Remote Config.
 * Provides centralized control over feature rollouts and experiments.
 */
@Singleton
class FeatureManager @Inject constructor(
    private val preferences: EncryptedPreferences,
    private val remoteConfig: FirebaseRemoteConfig,
    private val analyticsManager: AnalyticsManager
) {

    init {
        // Configure Remote Config
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600) // 1 hour
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)

        // Set default values
        remoteConfig.setDefaultsAsync(
            mapOf(
                KEY_NEW_CALENDAR_UI to false,
                KEY_CHAT_FEATURE to false,
                KEY_EXPENSE_TRACKER to true,
                KEY_CALENDAR_EXPERIMENT to "control"
            )
        )

        // Fetch and activate
        fetchAndActivate()
    }

    /**
     * Fetches the latest config from Firebase and activates it.
     */
    private fun fetchAndActivate() {
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val updated = task.result
                    Log.d(TAG, "Remote Config fetch successful. Updated: $updated")
                } else {
                    Log.w(TAG, "Remote Config fetch failed", task.exception)
                }
            }
    }

    /**
     * Checks if the new calendar UI feature is enabled.
     */
    val isNewCalendarEnabled: Boolean
        get() = getFeatureFlag(KEY_NEW_CALENDAR_UI, false)

    /**
     * Checks if the chat feature is enabled.
     */
    val isChatEnabled: Boolean
        get() = getFeatureFlag(KEY_CHAT_FEATURE, false)

    /**
     * Checks if the expense tracker feature is enabled.
     */
    val isExpenseTrackerEnabled: Boolean
        get() = getFeatureFlag(KEY_EXPENSE_TRACKER, true)

    /**
     * Gets a feature flag value with fallback to local preferences.
     *
     * @param key The feature flag key
     * @param defaultValue The default value if not found
     * @return The feature flag value
     */
    private fun getFeatureFlag(key: String, defaultValue: Boolean): Boolean {
        return try {
            remoteConfig.getBoolean(key)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get feature flag from Remote Config, using local preference", e)
            preferences.getBoolean(key, defaultValue)
        }
    }

    /**
     * Gets the calendar variant for A/B testing.
     *
     * @return The calendar variant to display
     */
    fun getCalendarVariant(): CalendarVariant {
        return try {
            when (remoteConfig.getString(KEY_CALENDAR_EXPERIMENT)) {
                "variant_a" -> CalendarVariant.A
                "variant_b" -> CalendarVariant.B
                else -> CalendarVariant.CONTROL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get calendar variant, using control", e)
            CalendarVariant.CONTROL
        }
    }

    /**
     * Logs feature usage to analytics.
     *
     * @param feature The feature name
     * @param userId The user ID
     */
    fun logFeatureUsage(feature: String, userId: String) {
        analyticsManager.logCustomEvent(
            "feature_used",
            mapOf(
                "feature" to feature,
                "user_id" to userId,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        Log.d(TAG, "Feature usage logged: $feature by user $userId")
    }

    /**
     * Manually refreshes the Remote Config values.
     */
    fun refresh() {
        fetchAndActivate()
    }

    /**
     * Gets all feature flags as a map for debugging.
     */
    fun getAllFeatureFlags(): Map<String, Boolean> {
        return mapOf(
            "new_calendar_ui" to isNewCalendarEnabled,
            "chat_feature" to isChatEnabled,
            "expense_tracker" to isExpenseTrackerEnabled
        )
    }

    companion object {
        private const val TAG = "FeatureManager"

        // Feature flag keys
        private const val KEY_NEW_CALENDAR_UI = "new_calendar_ui"
        private const val KEY_CHAT_FEATURE = "chat_feature"
        private const val KEY_EXPENSE_TRACKER = "expense_tracker"
        private const val KEY_CALENDAR_EXPERIMENT = "calendar_experiment"
    }
}
