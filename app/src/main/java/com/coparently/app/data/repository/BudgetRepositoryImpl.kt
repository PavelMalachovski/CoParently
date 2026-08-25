package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.BudgetDao
import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.BudgetEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreBudgetDataSource
import com.coparently.app.domain.family.FamilyKey
import com.coparently.app.domain.family.FamilyMemberRef
import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.BudgetAlert
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.repository.BudgetRepository
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
class BudgetRepositoryImpl @Inject constructor(
    private val budgetDao: BudgetDao,
    private val expenseDao: ExpenseDao,
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreBudgetDataSource: FirestoreBudgetDataSource
) : BudgetRepository {

    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    /**
     * Reads a `forMembersJson` column.
     *
     * A JSON array of plain strings, never a Gson serialisation of `FamilyMemberRef` — see that
     * type for why, and `ExpenseRepositoryImpl`, which reads its own column the same way.
     */
    private fun refsFrom(json: String): List<FamilyMemberRef> {
        val stored: List<String> = gson.fromJson(json, stringListType)
        return FamilyMemberRef.parse(stored)
    }

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

        // A budget that names nobody is the family's, and every expense in the category counts
        // against it. One that names members counts only what names them back — an untagged
        // expense is not silently charged to a child's budget, for the same reason a filter chip
        // does not show the untagged pile: see `FamilyMemberRef.names`.
        val scope = budget.forMembers
        return expenses.filter { expense ->
            !expense.date.isBefore(startOfMonth) && !expense.date.isAfter(endOfMonth) &&
                (scope.isEmpty() || refsFrom(expense.forMembersJson).any { it in scope })
        }.sumOf { it.amount }
    }

    override suspend fun addBudget(budget: Budget) {
        // Which relationship this belongs to, and therefore who may see it. Null while the
        // account is unpaired — see `ExpenseRepositoryImpl.addExpense`.
        val uid = firebaseAuthService.getCurrentUser()?.uid
        val owned = budget.copy(
            familyId = budget.familyId
                ?: FamilyKey.orNull(uid, userDao.getUserById(uid.orEmpty())?.partnerId)
        )
        budgetDao.insertBudget(owned.toEntity())

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        // A rejected/failed sync must never crash the app — the budget is already
        // saved locally and will re-sync later (same guard as ExpenseRepositoryImpl).
        try {
            firestoreBudgetDataSource.setBudget(owned.id, budgetToFirestoreMap(owned, firebaseUser.uid))
            val syncedBudget = owned.copy(syncedToFirestore = true)
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
        "forMembers" to FamilyMemberRef.store(budget.forMembers),
        "familyId" to (budget.familyId ?: ""),
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
                        familyId = (data["familyId"] as? String)?.takeIf { it.isNotEmpty() },
                        forMembers = FamilyMemberRef.parse(data["forMembers"])
                            .ifEmpty { FamilyMemberRef.fromLegacyChildId(data["childId"] as? String) },
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
            familyId = familyId,
            forMembers = refsFrom(forMembersJson),
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
            familyId = familyId,
            // `childId` is a dead column and is left null; see BudgetEntity.
            forMembersJson = gson.toJson(FamilyMemberRef.store(forMembers)),
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
