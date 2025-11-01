package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for CustodyScheduleEntity.
 * Provides methods to access custody schedule data from the Room database.
 */
@Dao
interface CustodyScheduleDao {
    /**
     * Gets all custody schedules as a Flow.
     */
    @Query("SELECT * FROM custody_schedules WHERE isActive = 1 ORDER BY dayOfWeek ASC")
    fun getAllActiveSchedules(): Flow<List<CustodyScheduleEntity>>

    /**
     * Gets all custody schedules (including inactive) as a Flow.
     */
    @Query("SELECT * FROM custody_schedules ORDER BY dayOfWeek ASC")
    fun getAllSchedules(): Flow<List<CustodyScheduleEntity>>

    /**
     * Gets custody schedules for a specific parent owner.
     */
    @Query("SELECT * FROM custody_schedules WHERE parentOwner = :parentOwner AND isActive = 1 ORDER BY dayOfWeek ASC")
    fun getSchedulesByParent(parentOwner: String): Flow<List<CustodyScheduleEntity>>

    /**
     * Gets custody schedules for a specific day of week.
     */
    @Query("SELECT * FROM custody_schedules WHERE dayOfWeek = :dayOfWeek AND isActive = 1")
    fun getSchedulesByDay(dayOfWeek: Int): Flow<List<CustodyScheduleEntity>>

    /**
     * Gets a custody schedule by ID.
     */
    @Query("SELECT * FROM custody_schedules WHERE id = :id")
    suspend fun getScheduleById(id: String): CustodyScheduleEntity?

    /**
     * Inserts a new custody schedule.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: CustodyScheduleEntity)

    /**
     * Inserts multiple custody schedules.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedules(schedules: List<CustodyScheduleEntity>)

    /**
     * Updates an existing custody schedule.
     */
    @Update
    suspend fun updateSchedule(schedule: CustodyScheduleEntity)

    /**
     * Deletes a custody schedule.
     */
    @Delete
    suspend fun deleteSchedule(schedule: CustodyScheduleEntity)

    /**
     * Deletes a custody schedule by ID.
     */
    @Query("DELETE FROM custody_schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: String)
}

