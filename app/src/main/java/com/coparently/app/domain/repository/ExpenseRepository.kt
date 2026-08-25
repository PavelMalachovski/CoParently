package com.coparently.app.domain.repository

import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.model.ExpenseSummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Repository interface for managing expenses.
 * Part of the domain layer in Clean Architecture.
 */
interface ExpenseRepository {
    /**
     * Gets all expenses as a Flow.
     */
    fun getAllExpenses(): Flow<List<Expense>>

    /**
     * Gets expenses for a specific child as a Flow.
     */
    fun getExpensesForChild(childId: String): Flow<List<Expense>>

    /**
     * Gets expenses for a specific date range as a Flow.
     */
    fun getExpensesForPeriod(start: LocalDate, end: LocalDate): Flow<List<Expense>>

    /**
     * Gets expenses by category as a Flow.
     */
    fun getExpensesByCategory(category: ExpenseCategory): Flow<List<Expense>>

    /**
     * Gets an expense by ID.
     */
    suspend fun getExpenseById(id: String): Expense?

    /**
     * Gets expense summary for a specific period.
     */
    suspend fun getExpenseSummary(start: LocalDate, end: LocalDate): ExpenseSummary

    /**
     * Adds a new expense.
     */
    suspend fun addExpense(expense: Expense)

    /**
     * Updates an existing expense.
     */
    suspend fun updateExpense(expense: Expense)

    /**
     * Deletes an expense.
     */
    suspend fun deleteExpense(expenseId: String)

    /**
     * Syncs expenses with Firestore.
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
