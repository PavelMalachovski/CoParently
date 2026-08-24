package com.coparently.app.di

import com.coparently.app.data.remote.firebase.FirebaseImageStorage
import com.coparently.app.data.repository.BudgetRepositoryImpl
import com.coparently.app.data.repository.ChangeRequestRepositoryImpl
import com.coparently.app.data.repository.ChildInfoRepositoryImpl
import com.coparently.app.data.repository.EventRepositoryImpl
import com.coparently.app.data.repository.ExpenseRepositoryImpl
import com.coparently.app.data.repository.FriendRepositoryImpl
import com.coparently.app.data.repository.GuestRepositoryImpl
import com.coparently.app.data.repository.MessageRepositoryImpl
import com.coparently.app.data.repository.PairingRepositoryImpl
import com.coparently.app.data.repository.PetRepositoryImpl
import com.coparently.app.data.repository.PreferencesRepositoryImpl
import com.coparently.app.domain.repository.BudgetRepository
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.EventImageStorage
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.FriendRepository
import com.coparently.app.domain.repository.GuestRepository
import com.coparently.app.domain.repository.MedicalPhotoStorage
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.PetPhotoStorage
import com.coparently.app.domain.repository.PetRepository
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

    /**
     * Provides MedicalPhotoStorage implementation (Firebase Cloud Storage).
     *
     * The same class as the two above — one Storage client, three shapes of object — but a
     * separate binding, because a caller that only ever attaches a photograph to a child should
     * not be handed the ability to delete receipts.
     */
    @Binds
    @Singleton
    abstract fun bindMedicalPhotoStorage(
        firebaseImageStorage: FirebaseImageStorage
    ): MedicalPhotoStorage

    /**
     * Provides FriendRepository implementation — the trusted third person (item 16), bound
     * separately from GuestRepository because a friend and a guest open different things.
     */
    @Binds
    @Singleton
    abstract fun bindFriendRepository(
        friendRepositoryImpl: FriendRepositoryImpl
    ): FriendRepository

    /**
     * Provides PetRepository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindPetRepository(
        petRepositoryImpl: PetRepositoryImpl
    ): PetRepository

    /**
     * Provides PetPhotoStorage implementation (Firebase Cloud Storage) — a separate binding
     * for the same reason MedicalPhotoStorage has one.
     */
    @Binds
    @Singleton
    abstract fun bindPetPhotoStorage(
        firebaseImageStorage: FirebaseImageStorage
    ): PetPhotoStorage

    /** Binds the Firestore-backed pairing repository. */
    @Binds
    @Singleton
    abstract fun bindPairingRepository(
        pairingRepositoryImpl: PairingRepositoryImpl
    ): PairingRepository

    /** Binds the Firestore-backed guest-access repository. */
    @Binds
    @Singleton
    abstract fun bindGuestRepository(
        guestRepositoryImpl: GuestRepositoryImpl
    ): GuestRepository
}

