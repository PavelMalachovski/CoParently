package com.coparently.app.di

import com.coparently.app.data.repository.ChildInfoRepositoryImpl
import com.coparently.app.data.repository.EventRepositoryImpl
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.EventRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module providing repository implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Provides EventRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindEventRepository(
        eventRepositoryImpl: EventRepositoryImpl
    ): EventRepository

    /**
     * Provides ChildInfoRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindChildInfoRepository(
        childInfoRepositoryImpl: ChildInfoRepositoryImpl
    ): ChildInfoRepository
}

