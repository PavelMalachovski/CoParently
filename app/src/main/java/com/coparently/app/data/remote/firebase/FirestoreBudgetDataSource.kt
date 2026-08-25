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
     * Gets the budgets of one co-parenting relationship as a Flow.
     *
     * Filtered on `familyId` for the reason [FirestoreExpenseDataSource.getAllExpenses] spells
     * out at length: the read rule is keyed on membership of the record's own family, Firestore
     * validates a query by its structure, and the old filter on the author merged two families
     * into one list.
     *
     * @param familyId `FamilyKey.of(myUid, partnerUid)`; empty or null closes the flow.
     */
    fun getAllBudgets(familyId: String?): Flow<List<Map<String, Any>>> = callbackFlow {
        if (familyId.isNullOrEmpty()) {
            close()
            return@callbackFlow
        }
        val subscription = budgetsCollection
            .whereEqualTo("familyId", familyId)
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
     * Fetches a single budget document by id, or `null` if it doesn't exist.
     *
     * Used by [BudgetRepositoryImpl.updateBudget] to read back the existing
     * `createdByFirebaseUid` before writing an update: `firestore.rules` requires that
     * field to stay unchanged on update, so the write path must know its current value
     * rather than re-stamping it with whichever user happens to be editing.
     */
    suspend fun getBudget(budgetId: String): Map<String, Any>? {
        val snapshot = budgetsCollection.document(budgetId).get().await()
        return if (snapshot.exists()) snapshot.data else null
    }

    /**
     * Deletes a budget from Firestore.
     */
    suspend fun deleteBudget(budgetId: String) {
        budgetsCollection.document(budgetId).delete().await()
    }
}
