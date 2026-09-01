package com.coparently.app.di

import com.coparently.app.data.remote.firebase.QRCodeService
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.mockk.mockk
import javax.inject.Singleton

/**
 * Replaces [FirebaseModule] for instrumented tests, so nothing reaches a real Firebase SDK.
 *
 * Without this the suite cannot run at all. Every provider in [FirebaseModule] calls a
 * `getInstance()` that requires a default `FirebaseApp`, and `FirebaseApp` is initialised by a
 * content provider that reads `google-services.json` — which is gitignored and has never existed
 * in CI. The first provider the graph touches therefore throws
 * `IllegalStateException: Default FirebaseApp is not initialized`, and because the graph is built
 * while `MainActivity` is being created, that kills the process and takes the whole
 * instrumentation run with it rather than failing one test.
 *
 * `HiltTestRunner` substituting `HiltTestApplication` does not prevent it: that stops
 * `CoPlanlyApplication.onCreate` from running, not the Hilt graph from being constructed.
 *
 * Shipping a fake `google-services.json` would also have worked and was rejected: the Google
 * Services and Crashlytics plugins in `app/build.gradle.kts` apply *only* when that file is
 * present, so adding one changes what every Android job builds — to fix a problem that belongs
 * to the tests. Replacing the module keeps the change inside `androidTest`.
 *
 * The mocks are relaxed because no test here asserts on Firebase: these tests are about
 * composables and about Room. What matters is that the graph builds and that
 * [com.coparently.app.data.local.CoPlanlyDatabase] is still the real one, so opening it through
 * SQLCipher (SEC-2) is genuinely exercised rather than stubbed out.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [FirebaseModule::class])
object FakeFirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseAnalytics(): FirebaseAnalytics = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseCrashlytics(): FirebaseCrashlytics = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions = mockk(relaxed = true)

    /**
     * The real service, not a mock: it encodes a bitmap with ZXing and touches no Firebase
     * despite its package. Provided here because [FirebaseModule] provides it, and this module
     * replaces that one wholesale.
     */
    @Provides
    @Singleton
    fun provideQRCodeService(): QRCodeService = QRCodeService()
}
