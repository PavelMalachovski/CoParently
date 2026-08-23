package com.coparently.app.di

import android.content.Context
import androidx.room.Room
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.BudgetDao
import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.dao.PetDao
import com.coparently.app.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module providing database and DAO dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** Schema versions older than the start of the migration chain (5->6). */
    private val PRE_MIGRATION_CHAIN_SCHEMAS = intArrayOf(1, 2, 3, 4)

    /**
     * Provides the Room database instance.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): CoPlanlyDatabase {
        return Room.databaseBuilder(
            context,
            CoPlanlyDatabase::class.java,
            "coparently_database"
        )
            .addMigrations(*com.coparently.app.data.local.DatabaseMigrations.ALL_MIGRATIONS)
            // The migration chain starts at 5->6 and is complete up to the current version;
            // installs older than schema v5 have no upgrade path (a v3 install crashed with
            // "migration from 3 to 9 required but not found" during the 2026-07 review), so
            // wipe ONLY those pre-chain schemas explicitly.
            //
            // Deliberately NOT using the blanket fallbackToDestructiveMigration()/
            // ...OnDowngrade(): with real family data on the device, a missing migration or a
            // downgrade must fail loudly (and be fixed with a proper migration) rather than
            // silently wiping the user's calendar, expenses and messages.
            .fallbackToDestructiveMigrationFrom(
                dropAllTables = true,
                *PRE_MIGRATION_CHAIN_SCHEMAS
            )
            .build()
    }

    /**
     * Provides EventDao.
     */
    @Provides
    fun provideEventDao(database: CoPlanlyDatabase): EventDao {
        return database.eventDao()
    }

    /**
     * Provides UserDao.
     */
    @Provides
    fun provideUserDao(database: CoPlanlyDatabase): UserDao {
        return database.userDao()
    }

    /**
     * Provides CustodyScheduleDao.
     */
    @Provides
    fun provideCustodyScheduleDao(database: CoPlanlyDatabase): CustodyScheduleDao {
        return database.custodyScheduleDao()
    }

    /**
     * Provides ChildInfoDao.
     */
    @Provides
    fun provideChildInfoDao(database: CoPlanlyDatabase): ChildInfoDao {
        return database.childInfoDao()
    }

    /**
     * Provides PetDao.
     */
    @Provides
    fun providePetDao(database: CoPlanlyDatabase): PetDao {
        return database.petDao()
    }

    /**
     * Provides MessageDao.
     */
    @Provides
    fun provideMessageDao(database: CoPlanlyDatabase): MessageDao {
        return database.messageDao()
    }

    /**
     * Provides ExpenseDao.
     */
    @Provides
    fun provideExpenseDao(database: CoPlanlyDatabase): ExpenseDao {
        return database.expenseDao()
    }

    /**
     * Provides BudgetDao.
     */
    @Provides
    fun provideBudgetDao(database: CoPlanlyDatabase): BudgetDao {
        return database.budgetDao()
    }

    /**
     * Provides CustodyModelDao.
     */
    @Provides
    fun provideCustodyModelDao(database: CoPlanlyDatabase): com.coparently.app.data.local.dao.CustodyModelDao {
        return database.custodyModelDao()
    }

    /**
     * Provides ChangeRequestDao.
     */
    @Provides
    fun provideChangeRequestDao(database: CoPlanlyDatabase): com.coparently.app.data.local.dao.ChangeRequestDao {
        return database.changeRequestDao()
    }
}
