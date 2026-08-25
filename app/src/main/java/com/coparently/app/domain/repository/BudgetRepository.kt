package com.coparently.app.domain.repository

import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.BudgetAlert
import com.coparently.app.domain.model.ExpenseCategory
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing budgets.
 * Part of the domain layer in Clean Architecture.
 */
interface BudgetRepository {
    /**
     * Gets all budgets as a Flow.
     */
    fun getAllBudgets(): Flow<List<Budget>>

    /**
     * Gets active budgets as a Flow.
     */
    fun getActiveBudgets(): Flow<List<Budget>>

    /**
     * Gets a budget by ID.
     */
    suspend fun getBudgetById(id: String): Budget?

    /**
     * Gets budget alerts for budgets that have exceeded their threshold.
     */
    suspend fun getBudgetAlerts(): List<BudgetAlert>

    /**
     * Gets the current spent amount for a specific budget.
     */
    suspend fun getSpentForBudget(budgetId: String): Double

    /**
     * Adds a new budget.
     */
    suspend fun addBudget(budget: Budget)

    /**
     * Updates an existing budget.
     */
    suspend fun updateBudget(budget: Budget)

    /**
     * Deletes a budget.
     */
    suspend fun deleteBudget(budgetId: String)

    /**
     * Syncs budgets with Firestore.
     */
    /**
     * Mirrors the remote side into Room and **never returns** — it collects a snapshot
     * listener for as long as its caller's scope lives.
     *
     * Named for the shape rather than for the subject (CQ-10). It was `syncWithFirestore()`,
     * the same name the one-shot repositories use, which made it look safe to await from
     * `SyncService.performFullSync()`. It is not: that call would hang, `SyncWorker` would be
     * killed at WorkManager's ten-minute ceiling, and sync would stop entirely with no
     * exception and no log. Call it from a scope that is allowed to run forever.
     */
    suspend fun observeRemote()
}
