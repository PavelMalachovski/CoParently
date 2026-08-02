package com.coparently.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.coparently.app.data.local.dao.AllergyDao
import com.coparently.app.data.local.dao.BudgetDao
import com.coparently.app.data.local.dao.ChangeRequestDao
import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.CustodyModelDao
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.GradeDao
import com.coparently.app.data.local.dao.MedicalRecordDao
import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.dao.SchoolEventDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.AllergyEntity
import com.coparently.app.data.local.entity.BudgetEntity
import com.coparently.app.data.local.entity.ChangeRequestEntity
import com.coparently.app.data.local.entity.ChildInfoEntity
import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.CustodyModelEntity
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.local.entity.ExpenseEntity
import com.coparently.app.data.local.entity.GradeEntity
import com.coparently.app.data.local.entity.MedicalRecordEntity
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.data.local.entity.SchoolEventEntity
import com.coparently.app.data.local.entity.UserEntity

/**
 * Room database for CoPlanly app.
 * Contains all entities and DAOs for local data storage.
 *
 * @see RoomDatabase
 */
@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        CustodyScheduleEntity::class,
        CustodyModelEntity::class,
        ChildInfoEntity::class,
        MessageEntity::class,
        ConversationEntity::class,
        ExpenseEntity::class,
        BudgetEntity::class,
        MedicalRecordEntity::class,
        AllergyEntity::class,
        GradeEntity::class,
        SchoolEventEntity::class,
        ChangeRequestEntity::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CoPlanlyDatabase : RoomDatabase() {
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

    /**
     * Provides access to CustodyModelDao.
     */
    abstract fun custodyModelDao(): CustodyModelDao

    /**
     * Provides access to ChildInfoDao.
     */
    abstract fun childInfoDao(): ChildInfoDao

    /**
     * Provides access to MessageDao.
     */
    abstract fun messageDao(): MessageDao

    /**
     * Provides access to ExpenseDao.
     */
    abstract fun expenseDao(): ExpenseDao

    /**
     * Provides access to BudgetDao.
     */
    abstract fun budgetDao(): BudgetDao

    /**
     * Provides access to MedicalRecordDao.
     */
    abstract fun medicalRecordDao(): MedicalRecordDao

    /**
     * Provides access to AllergyDao.
     */
    abstract fun allergyDao(): AllergyDao

    /**
     * Provides access to GradeDao.
     */
    abstract fun gradeDao(): GradeDao

    /**
     * Provides access to SchoolEventDao.
     */
    abstract fun schoolEventDao(): SchoolEventDao

    /**
     * Provides access to ChangeRequestDao.
     */
    abstract fun changeRequestDao(): ChangeRequestDao
}

