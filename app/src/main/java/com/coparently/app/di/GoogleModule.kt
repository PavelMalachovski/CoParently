package com.coparently.app.di

import com.coparently.app.data.remote.google.CredentialProvider
import com.coparently.app.data.remote.google.CredentialProviderImpl
import com.coparently.app.data.remote.google.GoogleCalendarApi
import com.coparently.app.data.remote.google.GoogleSignInService
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.sync.CalendarSyncRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module providing Google services dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleModule {

    /**
     * Provides CredentialProvider implementation.
     */
    @Binds
    @Singleton
    abstract fun bindCredentialProvider(
        credentialProviderImpl: CredentialProviderImpl
    ): CredentialProvider
}

/**
 * Module for providing Google services instances.
 */
@Module
@InstallIn(SingletonComponent::class)
object GoogleServiceModule {

    // GoogleCalendarApi is already @Singleton, so it will be provided automatically
    // GoogleSignInService is already @Singleton, so it will be provided automatically
    // EncryptedPreferences is already @Singleton, so it will be provided automatically
    // CalendarSyncRepository is already @Singleton, so it will be provided automatically
}

