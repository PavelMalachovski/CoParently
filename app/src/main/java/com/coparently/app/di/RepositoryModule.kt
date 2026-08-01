package com.coparently.app.di

import com.coparently.app.data.remote.firebase.FirebaseImageStorage
import com.coparently.app.data.repository.BudgetRepositoryImpl
import com.coparently.app.data.repository.ChangeRequestRepositoryImpl
import com.coparently.app.data.repository.ChildInfoRepositoryImpl
import com.coparently.app.data.repository.EventRepositoryImpl
import com.coparently.app.data.repository.ExpenseRepositoryImpl
import com.coparently.app.data.repository.MessageRepositoryImpl
import com.coparently.app.data.repository.PairingRepositoryImpl
import com.coparently.app.data.repository.PreferencesRepositoryImpl
import com.coparently.app.domain.repository.BudgetRepository
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.EventImageStorage
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.ReceiptStorage
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

    /**
     * Provides PreferencesRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(
        preferencesRepositoryImpl: PreferencesRepositoryImpl
    ): PreferencesRepository

    /**
     * Provides MessageRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        messageRepositoryImpl: MessageRepositoryImpl
    ): MessageRepository

    /**
     * Provides ExpenseRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindExpenseRepository(
        expenseRepositoryImpl: ExpenseRepositoryImpl
    ): ExpenseRepository

    /**
     * Provides BudgetRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindBudgetRepository(
        budgetRepositoryImpl: BudgetRepositoryImpl
    ): BudgetRepository

    /**
     * Provides ChangeRequestRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindChangeRequestRepository(
        changeRequestRepositoryImpl: ChangeRequestRepositoryImpl
    ): ChangeRequestRepository

    /**
     * Provides ReceiptStorage implementation (Firebase Cloud Storage).
     */
    @Binds
    @Singleton
    abstract fun bindReceiptStorage(
        firebaseImageStorage: FirebaseImageStorage
    ): ReceiptStorage

    /**
     * Provides EventImageStorage implementation (Firebase Cloud Storage).
     */
    @Binds
    @Singleton
    abstract fun bindEventImageStorage(
        firebaseImageStorage: FirebaseImageStorage
    ): EventImageStorage

    /** Binds the Firestore-backed pairing repository. */
    @Binds
    @Singleton
    abstract fun bindPairingRepository(
        pairingRepositoryImpl: PairingRepositoryImpl
    ): PairingRepository
}

