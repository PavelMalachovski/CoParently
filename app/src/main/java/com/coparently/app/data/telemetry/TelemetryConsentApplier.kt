package com.coparently.app.data.telemetry

import com.coparently.app.BuildConfig
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.telemetry.telemetryCollectionEnabled
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The **only** place that turns analytics and crash reporting on or off (REL-5).
 *
 * **Why one place.** Before this, three of them disagreed. The build flags
 * `ENABLE_ANALYTICS`/`ENABLE_CRASHLYTICS` were applied in `FirebaseModule`'s providers — and then
 * `CoPlanlyApplication.onCreate` called `setCrashlyticsCollectionEnabled(true)` unconditionally a
 * moment later, so the debug flag was defeated on every launch and debug crashes reported into the
 * production project regardless. A gate that any other line may overrule is not a gate, which is
 * why the setters now have exactly one caller and the providers only ever close them.
 *
 * **Why at runtime and not at injection.** A provider runs once, when something first asks for the
 * SDK. Consent changes afterwards — the user answers the first-run screen, or comes back to
 * Settings months later — and a decision applied only at injection would not take effect until the
 * process restarted. That is the difference between a switch and a label.
 *
 * **What actually stops collection before this runs** is the manifest, not this class:
 * `firebase_analytics_collection_enabled` and `firebase_crashlytics_collection_enabled` are both
 * `false` there, so both SDKs auto-initialise switched off and nothing is collected in the window
 * between process start and [start]. Removing either meta-data entry reopens that window silently.
 * *(Note that `firebase_analytics_collection_deactivated` is a different knob and must stay
 * `false`: setting it true disables Analytics permanently, and `setAnalyticsCollectionEnabled`
 * cannot re-enable it — a granted consent would then do nothing.)*
 *
 * Collection requires **both** the build flag and the user's answer; see
 * [telemetryCollectionEnabled] for why neither may override the other.
 */
@Singleton
class TelemetryConsentApplier @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val analytics: FirebaseAnalytics,
    private val crashlytics: FirebaseCrashlytics
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Begins following the stored consent for the life of the process.
     *
     * Called from `CoPlanlyApplication.onCreate`, beside `SessionProfileSynchronizer.start()` and
     * for the same reason: it belongs to the process, not to a screen, and a collector owned by a
     * ViewModel would stop applying the moment that screen left the back stack.
     */
    fun start() {
        scope.launch {
            preferencesRepository.getTelemetryConsentFlow()
                // The SDK setters persist their argument, so re-applying an unchanged answer
                // writes to disk for nothing. The flow re-emits on every answer, including a
                // re-answer to the same thing from Settings.
                .distinctUntilChanged()
                .collect { consent ->
                    // Each SDK against its own build flag. They are two BuildConfig entries and a
                    // build is allowed to set them apart, so deriving one from the other would
                    // make one of the two flags silently stop meaning anything.
                    analytics.setAnalyticsCollectionEnabled(
                        telemetryCollectionEnabled(consent, BuildConfig.ENABLE_ANALYTICS)
                    )
                    crashlytics.setCrashlyticsCollectionEnabled(
                        telemetryCollectionEnabled(consent, BuildConfig.ENABLE_CRASHLYTICS)
                    )
                }
        }
    }
}
