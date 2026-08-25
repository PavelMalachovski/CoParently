package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.ExpenseEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreExpenseDataSource
import com.coparently.app.data.sync.Tombstone
import com.coparently.app.domain.activity.ActivityAnnouncement
import com.coparently.app.domain.activity.ActivityAnnouncer
import com.coparently.app.domain.activity.ActivityEntityType
import com.coparently.app.domain.activity.ActivityKind
import com.coparently.app.domain.family.FamilyMemberRef
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
        // The DAO returns pending tombstones so the sync and delete paths can tell "deleted
        // here" from "never existed". To a caller asking on a user's behalf it is simply gone.
        return expenseDao.getExpenseById(id)?.takeIf { it.deletedAtMillis == null }?.toDomain()
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
        // Replaces the single `childId`, which is no longer written. Nothing is lost by
        // dropping it: no client ever set it, so every document in production carries "".
        "forMembers" to FamilyMemberRef.store(expense.forMembers),
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
        "createdAt" to expense.createdAt.format(dateTimeFormatter),
        // The share this expense was priced at, so both phones agree on a settled month even
        // after the family renegotiates. -1 rather than an omitted key: the read below narrows
        // through `Number` and a negative value is not a share, which is how "recorded before
        // the agreement existed" crosses the wire.
        "splitBasisPoints" to (expense.splitBasisPoints ?: -1)
    )

    /**
     * Deletes an expense, in a way the co-parent can actually find out about (CQ-3).
     *
     * The row is **tombstoned** rather than removed — hidden from every query at once, kept in
     * Room as an outbox entry until the deletion reaches Firestore. See
     * [com.coparently.app.data.repository.EventRepositoryImpl.deleteEvent]; the reasoning is
     * identical and so is the shape.
     *
     * The known gap this replaces was worse here than for events. The `expenses` delete rule
     * admits only the creator, so a co-parent's delete removed the local row, was refused
     * remotely, and the next sync pulled the expense back — a delete that undid itself in front
     * of the person who asked for it. Tombstoning does not widen that rule (`update` is
     * creator-only too, by an owner decision from the August 2026 walkthrough), so a co-parent's
     * delete is still refused. What changes is that it now *stays* refused visibly — the
     * tombstone is queued and retried rather than silently lost — and that the creator's own
     * delete finally reaches the other phone.
     */
    override suspend fun deleteExpense(expenseId: String) {
        // Read before marking: the announcement names the expense, and a tombstoned row is
        // invisible to every query that could name it afterwards.
        val deleted = expenseDao.getExpenseById(expenseId)?.toDomain()
        val deletedAtMillis = System.currentTimeMillis()
        expenseDao.markDeleted(expenseId, deletedAtMillis)
        deleted?.let { announce(it, ActivityKind.EXPENSE_DELETED) }

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val tombstoned = firestoreExpenseDataSource.tombstoneExpense(
            expenseId = expenseId,
            deletedAtMillis = deletedAtMillis,
            deletedBy = firebaseUser.uid
        )
        if (tombstoned.isSuccess) {
            expenseDao.deleteExpense(expenseId)
        } else {
            android.util.Log.w(
                "ExpenseRepo",
                "Expense tombstone not written; the deletion stays queued for the next sync",
                tombstoned.exceptionOrNull()
            )
        }
    }

    override suspend fun observeRemote() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val partnerId = userDao.getUserById(firebaseUser.uid)?.partnerId
        val creatorUids = listOfNotNull(firebaseUser.uid, partnerId)

        retryPendingDeletions(firebaseUser.uid)

        firestoreExpenseDataSource.getAllExpenses(creatorUids)
            .catch { e -> android.util.Log.w("ExpenseRepo", "Expense sync failed", e) }
            .collect { expenses ->
                expenses.forEach { data ->
                    val id = data["id"] as String

                    // The co-parent deleted it. Applied before anything is parsed, and without
                    // consulting any timestamp — see `SyncService.syncEvents` for why a
                    // deletion wins outright rather than by comparison.
                    if (Tombstone.isDeleted(data)) {
                        expenseDao.deleteExpense(id)
                        return@forEach
                    }

                    // The mirror image: this device deleted it and the tombstone has not been
                    // written yet, so the document is still alive. Writing it back here would
                    // undo the parent's own delete between two attempts to deliver it.
                    if (expenseDao.getExpenseById(id)?.deletedAtMillis != null) {
                        return@forEach
                    }

                    val expense = Expense(
                        id = id,
                        // A co-parent on a build that predates the reference type still writes
                        // `childId`, so it is read as a fallback. In practice it converts
                        // nothing — the field was never populated by any client.
                        forMembers = FamilyMemberRef.parse(data["forMembers"])
                            .ifEmpty { FamilyMemberRef.fromLegacyChildId(data["childId"] as? String) },
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
                            (data["createdByFirebaseUid"] as? String)?.takeIf { it.isNotEmpty() },
                        splitBasisPoints = (data["splitBasisPoints"] as? Number)?.toInt()
                            ?.takeIf { it >= 0 }
                    )
                    expenseDao.insertExpense(expense.toEntity())
                }
            }
    }

    /**
     * Re-attempts every deletion this device has not managed to write yet.
     *
     * Expenses have no general upload pass — `getUnsyncedExpenses()` exists but has never had a
     * caller, because an add or an edit pushes to Firestore inline. Deletions cannot work that
     * way: the inline push is exactly the one that fails when the phone is offline or the rule
     * refuses, and a failure there used to be the end of it. This is the retry the delete path
     * did not have.
     *
     * Deliberately scoped to tombstones rather than to everything `getUnsyncedExpenses()`
     * returns. Uploading the rest would change what an unsynced expense means and could
     * re-publish rows that were left unsynced on purpose; that is a separate question from
     * whether a delete survives, and it is not answered here.
     */
    private suspend fun retryPendingDeletions(userId: String) {
        val pending = expenseDao.getUnsyncedExpenses().filter { it.deletedAtMillis != null }
        for (entity in pending) {
            val deletedAtMillis = entity.deletedAtMillis ?: continue
            val tombstoned = firestoreExpenseDataSource.tombstoneExpense(
                expenseId = entity.id,
                deletedAtMillis = deletedAtMillis,
                deletedBy = entity.createdByFirebaseUid ?: userId
            )
            if (tombstoned.isSuccess) {
                expenseDao.deleteExpense(entity.id)
            } else {
                android.util.Log.w(
                    "ExpenseRepo",
                    "Expense tombstone for ${entity.id} not written; it stays queued",
                    tombstoned.exceptionOrNull()
                )
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
        val stringListType = object : TypeToken<List<String>>() {}.type
        val splitBetween: List<String> = gson.fromJson(splitBetweenJson, stringListType)
        val forMembers: List<String> = gson.fromJson(forMembersJson, stringListType)

        return Expense(
            id = id,
            forMembers = FamilyMemberRef.parse(forMembers),
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
            createdByFirebaseUid = createdByFirebaseUid,
            splitBasisPoints = splitBasisPoints
        )
    }

    private fun Expense.toEntity(): ExpenseEntity {
        return ExpenseEntity(
            id = id,
            // `childId` is a dead column and is left null; see ExpenseEntity.
            forMembersJson = gson.toJson(FamilyMemberRef.store(forMembers)),
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
            createdByFirebaseUid = createdByFirebaseUid,
            splitBasisPoints = splitBasisPoints
        )
    }
}
