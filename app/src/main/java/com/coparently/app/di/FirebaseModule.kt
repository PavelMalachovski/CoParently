package com.coparently.app.di

import com.coparently.app.data.repository.UserRepositoryImpl
import com.coparently.app.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
     */
    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore {
        return FirebaseFirestore.getInstance()
    }

    /**
     * Provides Firebase Messaging instance.
     */
    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging {
        return FirebaseMessaging.getInstance()
    }
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

