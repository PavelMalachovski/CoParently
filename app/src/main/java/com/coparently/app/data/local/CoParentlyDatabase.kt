package com.coparently.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.local.entity.UserEntity

/**
 * Room database for CoParently app.
 * Contains all entities and DAOs for local data storage.
 *
 * @see RoomDatabase
 */
@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        CustodyScheduleEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CoParentlyDatabase : RoomDatabase() {
    /**
     * Provides access to EventDao.
     */
    abstract fun eventDao(): EventDao

    /**
     * Provides access to UserDao.
     */
    abstract fun userDao(): UserDao

    /**
     * Provides access to CustodyScheduleDao.
     */
    abstract fun custodyScheduleDao(): CustodyScheduleDao
}

