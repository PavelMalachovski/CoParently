package com.coparently.app.di

import com.coparently.app.data.mlkit.MlKitReceiptTextRecognizer
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module providing receipt scanning implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReceiptModule {

    /**
     * Provides the on-device receipt text recognizer.
     */
    @Binds
    @Singleton
    abstract fun bindReceiptTextRecognizer(
        mlKitReceiptTextRecognizer: MlKitReceiptTextRecognizer
    ): ReceiptTextRecognizer
}
