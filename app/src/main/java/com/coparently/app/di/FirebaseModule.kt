package com.coparently.app.di

import com.coparently.app.data.repository.UserRepositoryImpl
import com.coparently.app.domain.repository.UserRepository
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module providing Firebase dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    /**
     * Provides Firebase Authentication instance.
     */
    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth {
        return FirebaseAuth.getInstance()
    }

    /**
     * Provides Firebase Firestore instance.
     * Uses the default database (default).
     * Offline persistence is enabled by default in Firestore SDK v24.0.0+
     * The app will automatically cache data locally for offline access.
     */
    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore {
        // Use the default database - Firebase uses "(default)" as the database name
        val db = FirebaseFirestore.getInstance()

        // In Firestore SDK v24.0.0+, offline persistence is enabled by default
        // No need to configure it explicitly unless you want to disable it
        android.util.Log.d("FirebaseModule", "Firestore initialized (offline persistence enabled by default)")

        return db
    }

    /**
     * Provides Firebase Cloud Storage instance (receipt photos and other attachments).
     */
    @Provides
    @Singleton
    fun provideFirebaseStorage(): com.google.firebase.storage.FirebaseStorage {
        return com.google.firebase.storage.FirebaseStorage.getInstance().apply {
            // Default retry windows are ~2 minutes, so a photo upload/download to a
            // bucket that is unreachable (e.g. Storage not enabled yet) hangs the Save
            // for that long before failing. Cap them so an upload fails fast and the
            // event/expense is saved promptly without the photo (with a warning).
            maxUploadRetryTimeMillis = STORAGE_RETRY_TIMEOUT_MS
            maxDownloadRetryTimeMillis = STORAGE_RETRY_TIMEOUT_MS
            maxOperationRetryTimeMillis = STORAGE_RETRY_TIMEOUT_MS
        }
    }

    /**
     * Provides Firebase Messaging instance.
     */
    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging {
        return FirebaseMessaging.getInstance()
    }

    /**
     * Provides Firebase Analytics instance.
     */
    @Provides
    @Singleton
    fun provideFirebaseAnalytics(): FirebaseAnalytics {
        return Firebase.analytics
    }

    /**
     * Provides Firebase Crashlytics instance.
     */
    @Provides
    @Singleton
    fun provideFirebaseCrashlytics(): FirebaseCrashlytics {
        return FirebaseCrashlytics.getInstance()
    }

    /**
     * Provides Firebase Functions for the pairing callables.
     *
     * The functions are deployed to us-central1, which is the SDK default, so
     * no explicit region is set here.
     */
    @Provides
    @Singleton
    fun provideFirebaseFunctions(): com.google.firebase.functions.FirebaseFunctions {
        return com.google.firebase.functions.FirebaseFunctions.getInstance()
    }

    /**
     * Provides QR Code service for generating pairing QR codes.
     */
    @Provides
    @Singleton
    fun provideQRCodeService(): com.coparently.app.data.remote.firebase.QRCodeService {
        return com.coparently.app.data.remote.firebase.QRCodeService()
    }

    /** Cap Storage retries at 20s so photo uploads fail fast instead of hanging ~2min. */
    private const val STORAGE_RETRY_TIMEOUT_MS = 20_000L
}

/**
 * Module for binding Firebase repository implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FirebaseRepositoryModule {

    /**
     * Provides UserRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindUserRepository(
        userRepositoryImpl: UserRepositoryImpl
    ): UserRepository
}

