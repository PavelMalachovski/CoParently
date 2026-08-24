package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.BudgetDao
import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.BudgetEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreBudgetDataSource
import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.BudgetAlert
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.repository.BudgetRepository
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
class BudgetRepositoryImpl @Inject constructor(
    private val budgetDao: BudgetDao,
    private val expenseDao: ExpenseDao,
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreBudgetDataSource: FirestoreBudgetDataSource
) : BudgetRepository {

    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getAllBudgets(): Flow<List<Budget>> {
        return budgetDao.getAllBudgets().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getActiveBudgets(): Flow<List<Budget>> {
        return budgetDao.getActiveBudgets().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getBudgetsForChild(childId: String): Flow<List<Budget>> {
        return budgetDao.getBudgetsForChild(childId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getBudgetById(id: String): Budget? {
        return budgetDao.getBudgetById(id)?.toDomain()
    }

    override suspend fun getBudgetAlerts(): List<BudgetAlert> {
        val activeBudgets = getActiveBudgets().first()
        val alerts = mutableListOf<BudgetAlert>()

        activeBudgets.forEach { budget ->
            val spent = getSpentForBudget(budget.id)
            val percentage = if (budget.monthlyLimit > 0) spent / budget.monthlyLimit else 0.0

            if (percentage >= budget.alertThreshold) {
                alerts.add(
                    BudgetAlert(
                        budgetId = budget.id,
                        currentSpent = spent,
                        limit = budget.monthlyLimit,
                        percentage = percentage,
                        category = budget.category
                    )
                )
            }
        }

        return alerts
    }

    override suspend fun getSpentForBudget(budgetId: String): Double {
        val budget = getBudgetById(budgetId) ?: return 0.0
        val now = LocalDate.now()
        val startOfMonth = now.withDayOfMonth(1)
        val endOfMonth = now.withDayOfMonth(now.lengthOfMonth())

        val expenses = expenseDao.getExpensesByCategory(budget.category.name).first()

        return expenses.filter {
            !it.date.isBefore(startOfMonth) && !it.date.isAfter(endOfMonth) &&
            (budget.childId == null || it.childId == budget.childId)
        }.sumOf { it.amount }
    }

    override suspend fun addBudget(budget: Budget) {
        val entity = budget.toEntity()
        budgetDao.insertBudget(entity)

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        // A rejected/failed sync must never crash the app — the budget is already
        // saved locally and will re-sync later (same guard as ExpenseRepositoryImpl).
        try {
            firestoreBudgetDataSource.setBudget(budget.id, budgetToFirestoreMap(budget, firebaseUser.uid))
            val syncedBudget = budget.copy(syncedToFirestore = true)
            budgetDao.insertBudget(syncedBudget.toEntity())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.w("BudgetRepo", "Budget Firestore sync failed; kept locally", e)
        }
    }

    override suspend fun updateBudget(budget: Budget) {
        val entity = budget.toEntity()
        budgetDao.insertBudget(entity)

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        // Same guard as addBudget: a rejected/failed sync must never crash the app.
        try {
            // Ownership is immutable in `firestore.rules` (update requires
            // request.resource.data.createdByFirebaseUid == resource.data.createdByFirebaseUid).
            // Read back the existing owner instead of re-stamping the current caller's uid —
            // addBudget's stamping is only correct for a brand-new document. Without this,
            // the co-parent editing a budget they didn't create would flip the owner field,
            // Firestore would reject the write, and the edit would silently fail to sync.
            val existingOwnerUid = firestoreBudgetDataSource.getBudget(budget.id)
                ?.get("createdByFirebaseUid") as? String
            val ownerUid = existingOwnerUid ?: firebaseUser.uid
            firestoreBudgetDataSource.setBudget(budget.id, budgetToFirestoreMap(budget, ownerUid))
            val syncedBudget = budget.copy(syncedToFirestore = true)
            budgetDao.insertBudget(syncedBudget.toEntity())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.w("BudgetRepo", "Budget Firestore sync failed; kept locally", e)
        }
    }

    /**
     * Builds the Firestore document map for a budget, stamping [ownerUid] as
     * `createdByFirebaseUid`.
     *
     * Mirrors the expenses schema so the Firestore rules can gate ownership/partner-sharing
     * consistently. Budgets have no `sharedWith` field — a budget is visible to the paired
     * co-parent via the `isPartnerOf()` relationship, not a per-document list.
     *
     * Callers decide [ownerUid]: [addBudget] passes the current user (a new document has no
     * prior owner), while [updateBudget] passes the document's existing owner so ownership
     * stays immutable across edits, as `firestore.rules` requires.
     */
    private fun budgetToFirestoreMap(budget: Budget, ownerUid: String): Map<String, Any> = mapOf(
        "id" to budget.id,
        "childId" to (budget.childId ?: ""),
        "category" to budget.category.name,
        "monthlyLimit" to budget.monthlyLimit,
        "currency" to budget.currency,
        "alertThreshold" to budget.alertThreshold,
        "isActive" to budget.isActive,
        "createdByFirebaseUid" to ownerUid,
        "createdAt" to budget.createdAt.format(dateTimeFormatter)
    )

    override suspend fun deleteBudget(budgetId: String) {
        budgetDao.deleteBudget(budgetId)

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            // Same guard as addBudget above: the row is already gone locally, and a
            // rejected remote delete must not take down the app.
            try {
                firestoreBudgetDataSource.deleteBudget(budgetId)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("BudgetRepo", "Budget Firestore delete failed", e)
            }
        }
    }

    override suspend fun observeRemote() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val partnerId = userDao.getUserById(firebaseUser.uid)?.partnerId
        val creatorUids = listOfNotNull(firebaseUser.uid, partnerId)

        firestoreBudgetDataSource.getAllBudgets(creatorUids)
            .catch { e -> android.util.Log.w("BudgetRepo", "Budget sync failed", e) }
            .collect { budgets ->
                budgets.forEach { data ->
                    val budget = Budget(
                        id = data["id"] as String,
                        childId = (data["childId"] as? String)?.takeIf { it.isNotEmpty() },
                        category = ExpenseCategory.valueOf(data["category"] as String),
                        monthlyLimit = (data["monthlyLimit"] as Number).toDouble(),
                        currency = data["currency"] as String,
                        alertThreshold = (data["alertThreshold"] as Number).toDouble(),
                        isActive = (data["isActive"] as? Boolean) ?: true,
                        createdAt = LocalDateTime.parse(data["createdAt"] as String, dateTimeFormatter),
                        syncedToFirestore = true
                    )
                    budgetDao.insertBudget(budget.toEntity())
                }
            }
    }

    private fun BudgetEntity.toDomain(): Budget {
        return Budget(
            id = id,
            childId = childId,
            category = ExpenseCategory.valueOf(category),
            monthlyLimit = monthlyLimit,
            currency = currency,
            alertThreshold = alertThreshold,
            isActive = isActive,
            createdAt = createdAt,
            syncedToFirestore = syncedToFirestore
        )
    }

    private fun Budget.toEntity(): BudgetEntity {
        return BudgetEntity(
            id = id,
            childId = childId,
            category = category.name,
            monthlyLimit = monthlyLimit,
            currency = currency,
            alertThreshold = alertThreshold,
            isActive = isActive,
            createdAt = createdAt,
            syncedToFirestore = syncedToFirestore
        )
    }
}
