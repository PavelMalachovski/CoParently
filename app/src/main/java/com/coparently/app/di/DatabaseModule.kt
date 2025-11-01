package com.coparently.app.di

import android.content.Context
import androidx.room.Room
import com.coparently.app.data.local.CoParentlyDatabase
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.dao.EventDao
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

    /**
     * Provides the Room database instance.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): CoParentlyDatabase {
        return Room.databaseBuilder(
            context,
            CoParentlyDatabase::class.java,
            "coparently_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * Provides EventDao.
     */
    @Provides
    fun provideEventDao(database: CoParentlyDatabase): EventDao {
        return database.eventDao()
    }

    /**
     * Provides UserDao.
     */
    @Provides
    fun provideUserDao(database: CoParentlyDatabase): UserDao {
        return database.userDao()
    }

    /**
     * Provides CustodyScheduleDao.
     */
    @Provides
    fun provideCustodyScheduleDao(database: CoParentlyDatabase): CustodyScheduleDao {
        return database.custodyScheduleDao()
    }
}

