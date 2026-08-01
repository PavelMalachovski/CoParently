package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data source for managing expenses in Firestore.
 */
@Singleton
class FirestoreExpenseDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val expensesCollection = firestore.collection("expenses")

    /**
     * Gets expenses created by any of [creatorUids] (the current user, plus the paired
     * co-parent when paired) as a Flow.
     *
     * An unfiltered collection query is rejected outright by `firestore.rules`: Firestore
     * validates a *query* by checking whether its structure guarantees every possible
     * result satisfies the security rule, not by inspecting the actual documents returned.
     * Since the read rule is keyed on `createdByFirebaseUid`, the query must filter on that
     * same field for Firestore to accept it — the same reasoning that makes
     * [FirestoreEventDataSource.observeEventsSharedWith] filter on `sharedWith`.
     *
     * @param creatorUids Firebase UIDs whose expenses to include (1 when unpaired, 2 when paired)
     */
    fun getAllExpenses(creatorUids: List<String>): Flow<List<Map<String, Any>>> = callbackFlow {
        if (creatorUids.isEmpty()) {
            close()
            return@callbackFlow
        }
        val subscription = expensesCollection
            .whereIn("createdByFirebaseUid", creatorUids)
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val expenses = snapshot.documents.map { doc ->
                        doc.data?.plus("id" to doc.id) ?: emptyMap()
                    }
                    trySend(expenses)
                }
            }

        awaitClose { subscription.remove() }
    }

    /**
     * Adds or updates an expense in Firestore.
     */
    suspend fun setExpense(expenseId: String, expenseData: Map<String, Any>) {
        expensesCollection.document(expenseId).set(expenseData).await()
    }

    /**
     * Fetches a single expense document by id, or `null` if it doesn't exist.
     *
     * Used by [com.coparently.app.data.repository.ExpenseRepositoryImpl.updateExpense] to read
     * back the existing `createdByFirebaseUid` before writing an update: `firestore.rules`
     * requires that field to stay unchanged on update, so the write path must know its current
     * value rather than re-stamping it with whichever parent happens to be editing.
     */
    suspend fun getExpense(expenseId: String): Map<String, Any>? {
        val snapshot = expensesCollection.document(expenseId).get().await()
        return if (snapshot.exists()) snapshot.data else null
    }

    /**
     * Deletes an expense from Firestore.
     */
    suspend fun deleteExpense(expenseId: String) {
        expensesCollection.document(expenseId).delete().await()
    }
}
