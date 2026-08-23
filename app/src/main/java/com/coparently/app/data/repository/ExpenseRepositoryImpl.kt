package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.ExpenseEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreExpenseDataSource
import com.coparently.app.domain.activity.ActivityAnnouncement
import com.coparently.app.domain.activity.ActivityAnnouncer
import com.coparently.app.domain.activity.ActivityEntityType
import com.coparently.app.domain.activity.ActivityKind
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
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreExpenseDataSource: FirestoreExpenseDataSource,
    private val activityAnnouncer: ActivityAnnouncer
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
        // A brand-new expense has no prior owner, so the current user is the owner — stamped on
        // the Room row too, so the client can enforce creator-only editing without a network
        // read. Null when signed out; the row then reads as "editable by both", which is all
        // this device can honestly say about it.
        val owned = expense.copy(
            createdByFirebaseUid = expense.createdByFirebaseUid
                ?: firebaseAuthService.getCurrentUser()?.uid
        )

        // Room is the source of truth — persist locally first so the expense is never
        // lost, even if the Firestore push below fails.
        expenseDao.insertExpense(owned.toEntity())

        announce(owned, ActivityKind.EXPENSE_ADDED)

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        pushToFirestore(owned, ownerUid = owned.createdByFirebaseUid ?: firebaseUser.uid)
    }

    override suspend fun updateExpense(expense: Expense) {
        expenseDao.insertExpense(expense.toEntity())
        announce(expense, ActivityKind.EXPENSE_UPDATED)

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        try {
            // Ownership is immutable in `firestore.rules` (update requires
            // request.resource.data.createdByFirebaseUid == resource.data.createdByFirebaseUid).
            // Read back the existing owner instead of re-stamping the caller's uid — the
            // stamping addExpense does is only correct for a document that does not exist yet.
            // Without this, a co-parent editing an expense they didn't create would flip the
            // owner field, Firestore would reject the write, and the edit would sit in Room
            // with syncedToFirestore = false forever, failing again on every retry.
            // Same fix as BudgetRepositoryImpl.updateBudget.
            val existingOwnerUid = firestoreExpenseDataSource.getExpense(expense.id)
                ?.get("createdByFirebaseUid") as? String
            pushToFirestore(expense, ownerUid = existingOwnerUid ?: firebaseUser.uid)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.w("ExpenseRepo", "Expense owner lookup failed; kept locally", e)
        }
    }

    /**
     * Writes [expense] to Firestore stamped with [ownerUid] and, on success, marks the local
     * row as synced.
     *
     * A rejected or failed write must never crash the app — the expense is already saved
     * locally and will re-sync later. (A PERMISSION_DENIED here used to be fatal.)
     */
    private suspend fun pushToFirestore(expense: Expense, ownerUid: String) {
        try {
            firestoreExpenseDataSource.setExpense(expense.id, expenseToFirestoreMap(expense, ownerUid))
            expenseDao.insertExpense(expense.copy(syncedToFirestore = true).toEntity())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.w("ExpenseRepo", "Expense Firestore sync failed; kept locally", e)
        }
    }

    /**
     * Builds the Firestore document map for an expense, stamping [ownerUid] as
     * `createdByFirebaseUid`.
     *
     * `createdByFirebaseUid` mirrors the events schema so the Firestore rules can gate
     * ownership consistently; `paidBy` stays for split/summary logic. Callers decide
     * [ownerUid]: [addExpense] passes the current user, [updateExpense] passes the document's
     * existing owner so ownership stays immutable across edits, as `firestore.rules` requires.
     */
    private fun expenseToFirestoreMap(expense: Expense, ownerUid: String): Map<String, Any> = mapOf(
        "id" to expense.id,
        "childId" to (expense.childId ?: ""),
        "title" to expense.title,
        "amount" to expense.amount,
        "currency" to expense.currency,
        "category" to expense.category.name,
        "createdByFirebaseUid" to ownerUid,
        "paidBy" to expense.paidBy,
        "splitBetween" to expense.splitBetween,
        "date" to expense.date.format(dateFormatter),
        "receiptUrl" to (expense.receiptUrl ?: ""),
        "notes" to (expense.notes ?: ""),
        "createdAt" to expense.createdAt.format(dateTimeFormatter)
    )

    override suspend fun deleteExpense(expenseId: String) {
        // Read before deleting: the announcement names the expense, and after the row is gone
        // there is nothing left to name it with.
        val deleted = expenseDao.getExpenseById(expenseId)?.toDomain()
        expenseDao.deleteExpense(expenseId)
        deleted?.let { announce(it, ActivityKind.EXPENSE_DELETED) }

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            // Same guard as addExpense above, which the delete path never got: the row is
            // already gone locally, and a rejected remote delete arrives from Firestore's
            // write-rejection path and takes the whole app down if nothing catches it.
            //
            // KNOWN GAP (not a transient failure): the `expenses` delete rule admits only the
            // creator, so a co-parent deleting a shared expense loses the local row while the
            // remote document survives, and the next sync pulls it back.
            //
            // Widening that rule to `isPartnerOf` the way `update` does was tried on this
            // branch and reverted after a device sweep reported PERMISSION_DENIED on the
            // *creator's own* delete. That attribution has since been disproved: under the
            // exact reverted ruleset the creator's delete succeeds for every shape the
            // creator's `users` document can have, and the two rulesets differ only for the
            // co-parent. See firestore-tests/rules/expenses-delete-incident.test.js.
            //
            // A denial here is real but comes from document state, not from the rule clause,
            // and reproduces under the shipped rules too: `resource` is null when the remote
            // document never landed (pushToFirestore swallows a failed create), and the
            // ownership read errors on documents written before `createdByFirebaseUid` was
            // stamped. Both raise exactly the log line the sweep recorded.
            try {
                firestoreExpenseDataSource.deleteExpense(expenseId)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("ExpenseRepo", "Expense Firestore delete failed", e)
            }
        }
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val partnerId = userDao.getUserById(firebaseUser.uid)?.partnerId
        val creatorUids = listOfNotNull(firebaseUser.uid, partnerId)

        firestoreExpenseDataSource.getAllExpenses(creatorUids)
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
                        syncedToFirestore = true,
                        createdByFirebaseUid =
                            (data["createdByFirebaseUid"] as? String)?.takeIf { it.isNotEmpty() }
                    )
                    expenseDao.insertExpense(expense.toEntity())
                }
            }
    }

    /**
     * Tells the co-parent about an expense.
     *
     * Nothing is suppressed here: an expense is a shared fact by construction — there is no
     * private expense, and the pair's balance is the reason both parents opened the app. That is
     * the difference from `EventRepositoryImpl`, which must suppress a private event.
     *
     * The amount is passed already formatted, with its currency beside it. The app never converts
     * between currencies (CLAUDE.md), so a reader must be able to group by currency without
     * parsing the formatted string back apart.
     *
     * Never throws — see `ActivityAnnouncer`. A parent's expense must not fail to save because
     * their co-parent's chat listener is down.
     */
    private suspend fun announce(expense: Expense, kind: ActivityKind) {
        val myUid = firebaseAuthService.getCurrentUser()?.uid ?: return
        activityAnnouncer.announce(
            announcement = ActivityAnnouncement(
                kind = kind,
                entityType = ActivityEntityType.EXPENSE,
                entityId = expense.id,
                title = expense.title,
                whenIso = expense.date.format(dateFormatter),
                amount = "${expense.amount} ${expense.currency}",
                currency = expense.currency
            ),
            senderName = userDao.getUserById(myUid)?.name.orEmpty()
        )
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
            syncedToFirestore = syncedToFirestore,
            createdByFirebaseUid = createdByFirebaseUid
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
            syncedToFirestore = syncedToFirestore,
            createdByFirebaseUid = createdByFirebaseUid
        )
    }
}
