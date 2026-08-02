package com.coparently.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.coparently.app.data.session.SessionProfileSynchronizer
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
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

        // Enable Crashlytics collection
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)

        // Make sure the signed-in user has a profile document carrying their name and
        // email. This has to happen here, not on a screen: the co-parent reads that
        // document, and a session restored from a build that never wrote one would
        // otherwise stay nameless forever.
        sessionProfileSynchronizer.start()

        // Schedule periodic background sync
        // Note: WorkManager is initialized via Hilt, so we can schedule work here
        com.coparently.app.data.sync.SyncWorker.schedulePeriodicSync(this)
    }
}

