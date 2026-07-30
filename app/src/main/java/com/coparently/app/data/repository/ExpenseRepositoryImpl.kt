package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.entity.ExpenseEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreExpenseDataSource
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.model.ExpenseSummary
import com.coparently.app.domain.repository.ExpenseRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreExpenseDataSource: FirestoreExpenseDataSource
) : ExpenseRepository {

    private val gson = Gson()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getAllExpenses(): Flow<List<Expense>> {
        return expenseDao.getAllExpenses().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getExpensesForChild(childId: String): Flow<List<Expense>> {
        return expenseDao.getExpensesForChild(childId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getExpensesForPeriod(start: LocalDate, end: LocalDate): Flow<List<Expense>> {
        return expenseDao.getExpensesForPeriod(start, end).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getExpensesByCategory(category: ExpenseCategory): Flow<List<Expense>> {
        return expenseDao.getExpensesByCategory(category.name).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getExpenseById(id: String): Expense? {
        return expenseDao.getExpenseById(id)?.toDomain()
    }

    override suspend fun getExpenseSummary(start: LocalDate, end: LocalDate): ExpenseSummary {
        val expenses = getExpensesForPeriod(start, end).first()

        val totalAmount = expenses.sumOf { it.amount }
        val byCategory = expenses.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
        val byPayer = expenses.groupBy { it.paidBy }
            .mapValues { (_, list) -> list.sumOf { it.amount } }

        return ExpenseSummary(
            totalAmount = totalAmount,
            expenseCount = expenses.size,
            byCategory = byCategory,
            byPayer = byPayer
        )
    }

    override suspend fun addExpense(expense: Expense) {
        // Room is the source of truth — persist locally first so the expense is never
        // lost, even if the Firestore push below fails.
        val entity = expense.toEntity()
        expenseDao.insertExpense(entity)

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val expenseData = mapOf(
            "id" to expense.id,
            "childId" to (expense.childId ?: ""),
            "title" to expense.title,
            "amount" to expense.amount,
            "currency" to expense.currency,
            "category" to expense.category.name,
            // createdByFirebaseUid mirrors the events schema so the Firestore rules can
            // gate ownership consistently; paidBy stays for split/summary logic.
            "createdByFirebaseUid" to firebaseUser.uid,
            "paidBy" to expense.paidBy,
            "splitBetween" to expense.splitBetween,
            "date" to expense.date.format(dateFormatter),
            "receiptUrl" to (expense.receiptUrl ?: ""),
            "notes" to (expense.notes ?: ""),
            "createdAt" to expense.createdAt.format(dateTimeFormatter)
        )
        // A rejected/failed sync must never crash the app — the expense is already saved
        // locally and will re-sync later. (A PERMISSION_DENIED here used to be fatal.)
        try {
            firestoreExpenseDataSource.setExpense(expense.id, expenseData)
            expenseDao.insertExpense(expense.copy(syncedToFirestore = true).toEntity())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.w("ExpenseRepo", "Expense Firestore sync failed; kept locally", e)
        }
    }

    override suspend fun updateExpense(expense: Expense) {
        addExpense(expense) // Same logic for add/update
    }

    override suspend fun deleteExpense(expenseId: String) {
        expenseDao.deleteExpense(expenseId)

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            // Same guard as addExpense above, which the delete path never got: the row is
            // already gone locally, and a rejected remote delete (PERMISSION_DENIED against the
            // deployed rules) arrives from Firestore's write-rejection path and takes the whole
            // app down if nothing catches it.
            try {
                firestoreExpenseDataSource.deleteExpense(expenseId)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("ExpenseRepo", "Expense Firestore delete failed", e)
            }
        }
    }

    override suspend fun syncWithFirestore() {
        firestoreExpenseDataSource.getAllExpenses()
            .catch { e -> android.util.Log.w("ExpenseRepo", "Expense sync failed", e) }
            .collect { expenses ->
                expenses.forEach { data ->
                    val expense = Expense(
                        id = data["id"] as String,
                        childId = (data["childId"] as? String)?.takeIf { it.isNotEmpty() },
                        title = data["title"] as String,
                        amount = (data["amount"] as Number).toDouble(),
                        currency = data["currency"] as String,
                        category = ExpenseCategory.valueOf(data["category"] as String),
                        paidBy = data["paidBy"] as String,
                        splitBetween = (data["splitBetween"] as? List<String>) ?: emptyList(),
                        date = LocalDate.parse(data["date"] as String, dateFormatter),
                        receiptUrl = (data["receiptUrl"] as? String)?.takeIf { it.isNotEmpty() },
                        notes = (data["notes"] as? String)?.takeIf { it.isNotEmpty() },
                        createdAt = LocalDateTime.parse(data["createdAt"] as String, dateTimeFormatter),
                        syncedToFirestore = true
                    )
                    expenseDao.insertExpense(expense.toEntity())
                }
            }
    }

    private fun ExpenseEntity.toDomain(): Expense {
        val splitListType = object : TypeToken<List<String>>() {}.type
        val splitBetween: List<String> = gson.fromJson(splitBetweenJson, splitListType)

        return Expense(
            id = id,
            childId = childId,
            title = title,
            amount = amount,
            currency = currency,
            category = ExpenseCategory.valueOf(category),
            paidBy = paidBy,
            splitBetween = splitBetween,
            date = date,
            receiptUrl = receiptUrl,
            notes = notes,
            createdAt = createdAt,
            syncedToFirestore = syncedToFirestore
        )
    }

    private fun Expense.toEntity(): ExpenseEntity {
        return ExpenseEntity(
            id = id,
            childId = childId,
            title = title,
            amount = amount,
            currency = currency,
            category = category.name,
            paidBy = paidBy,
            splitBetweenJson = gson.toJson(splitBetween),
            date = date,
            receiptUrl = receiptUrl,
            notes = notes,
            createdAt = createdAt,
            syncedToFirestore = syncedToFirestore
        )
    }
}
