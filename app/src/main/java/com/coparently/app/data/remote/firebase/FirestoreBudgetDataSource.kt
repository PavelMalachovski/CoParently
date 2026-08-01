package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data source for managing budgets in Firestore.
 */
@Singleton
class FirestoreBudgetDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val budgetsCollection = firestore.collection("budgets")

    /**
     * Gets budgets created by any of [creatorUids] (the current user, plus the paired
     * co-parent when paired) as a Flow.
     *
     * An unfiltered collection query is rejected outright by `firestore.rules`: Firestore
     * validates a *query* by checking whether its structure guarantees every possible
     * result satisfies the security rule, not by inspecting the actual documents returned.
     * Since the `budgets` read rule is keyed on `createdByFirebaseUid`, the query must
     * filter on that same field for Firestore to accept it — mirroring
     * [FirestoreExpenseDataSource.getAllExpenses].
     *
     * @param creatorUids Firebase UIDs whose budgets to include (1 when unpaired, 2 when paired)
     */
    fun getAllBudgets(creatorUids: List<String>): Flow<List<Map<String, Any>>> = callbackFlow {
        if (creatorUids.isEmpty()) {
            close()
            return@callbackFlow
        }
        val subscription = budgetsCollection
            .whereIn("createdByFirebaseUid", creatorUids)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val budgets = snapshot.documents.map { doc ->
                        doc.data?.plus("id" to doc.id) ?: emptyMap()
                    }
                    trySend(budgets)
                }
            }

        awaitClose { subscription.remove() }
    }

    /**
     * Adds or updates a budget in Firestore.
     */
    suspend fun setBudget(budgetId: String, budgetData: Map<String, Any>) {
        budgetsCollection.document(budgetId).set(budgetData).await()
    }

    /**
     * Deletes a budget from Firestore.
     */
    suspend fun deleteBudget(budgetId: String) {
        budgetsCollection.document(budgetId).delete().await()
    }
}
