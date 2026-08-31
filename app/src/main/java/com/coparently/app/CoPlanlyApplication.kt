package com.coparently.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.coparently.app.data.session.SessionProfileSynchronizer
import com.coparently.app.data.sync.SyncWorker
import com.coparently.app.data.telemetry.TelemetryConsentApplier
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for CoPlanly.
 * Marks the application for Hilt dependency injection and initializes Firebase services.
 *
 * @see HiltAndroidApp
 */
@HiltAndroidApp
class CoPlanlyApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Repairs the signed-in user's Firestore profile at every session boundary.
     * Field-injected rather than created here so Hilt owns its dependencies.
     */
    @Inject
    lateinit var sessionProfileSynchronizer: SessionProfileSynchronizer

    /**
     * Applies the analytics and crash-reporting consent for the life of the process (REL-5).
     * Field-injected for the same reason as [sessionProfileSynchronizer], and started here
     * because it belongs to the process rather than to any screen.
     */
    @Inject
    lateinit var telemetryConsentApplier: TelemetryConsentApplier

    /**
     * Provides WorkManager configuration with HiltWorkerFactory.
     * This enables dependency injection in WorkManager workers.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        val firebaseApp = FirebaseApp.initializeApp(this)
        if (firebaseApp != null) {
            android.util.Log.d("CoPlanlyApplication", "Firebase initialized successfully")
            android.util.Log.d("CoPlanlyApplication", "Project ID: ${firebaseApp.options.projectId}")
            android.util.Log.d("CoPlanlyApplication", "API Key: ${firebaseApp.options.apiKey?.take(10)}...")
        } else {
            android.util.Log.e("CoPlanlyApplication", "Firebase initialization failed")
        }

        // Analytics and Crashlytics follow the user's answer from here on, and nothing else
        // touches their setters (REL-5). This line used to read
        // `FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)` —
        // unconditional, and run a moment after `FirebaseModule` had just applied
        // `BuildConfig.ENABLE_CRASHLYTICS`, so the debug flag was overruled on every launch and
        // developer crashes reported into the production project regardless of it.
        telemetryConsentApplier.start()

        // Make sure the signed-in user has a profile document carrying their name and
        // email. This has to happen here, not on a screen: the co-parent reads that
        // document, and a session restored from a build that never wrote one would
        // otherwise stay nameless forever.
        sessionProfileSynchronizer.start()

        // Schedule periodic background sync
        // Note: WorkManager is initialized via Hilt, so we can schedule work here
        SyncWorker.schedulePeriodicSync(this)

        // ...and one right now, plus one every time the app comes back to the foreground.
        SyncWorker.syncNow(this)
        registerActivityLifecycleCallbacks(ForegroundSyncTrigger())
    }

    /**
     * Runs a sync whenever the app returns to the foreground.
     *
     * The periodic worker is enqueued with `ExistingPeriodicWorkPolicy.UPDATE`, which keeps the
     * existing period, so opening the app did not bring anything down from the server — a
     * co-parent's change could be up to fifteen minutes stale, and much longer under Doze, with
     * the only manual lever buried in Settings → Sync.
     *
     * `registerActivityLifecycleCallbacks` rather than `ProcessLifecycleOwner`: the same signal
     * without adding `androidx.lifecycle:lifecycle-process` for one observer. A zero-to-one
     * transition in the started count is the app becoming visible; a rotation dips to zero only
     * momentarily and re-enqueues a unique KEEP request, which coalesces into the run already
     * pending.
     */
    private inner class ForegroundSyncTrigger : ActivityLifecycleCallbacks {
        private var startedActivities = 0

        override fun onActivityStarted(activity: Activity) {
            if (startedActivities++ == 0) {
                SyncWorker.syncNow(this@CoPlanlyApplication)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedActivities > 0) startedActivities--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

