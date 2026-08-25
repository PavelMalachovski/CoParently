package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coparently.app.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for budgets.
 */
@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets")
    fun getAllBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    fun getActiveBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getBudgetById(id: String): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteBudget(id: String)

    @Query("SELECT * FROM budgets WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedBudgets(): List<BudgetEntity>

    /**
     * Stamps [familyId] on every budgets row that names no family yet.
     *
     * The backfill half of docs/DESIGN-multi-family.md M-2: rows written before the column
     * existed, and rows written before this parent paired, both read as null. A device knows of
     * exactly one co-parenting relationship, so an unstamped row can only belong to that one.
     *
     * Null rows only. A row that already names a family keeps it — re-deriving the stamp is what
     * would let a re-pairing silently move a record into a different household.
     *
     * @return How many rows were stamped.
     */
    @Query("UPDATE budgets SET familyId = :familyId WHERE familyId IS NULL")
    suspend fun stampFamilyId(familyId: String): Int
}
