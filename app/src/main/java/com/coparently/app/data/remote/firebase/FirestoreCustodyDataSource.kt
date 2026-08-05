package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the one custody document a pair shares.
 *
 * Room stores `momDaysPattern` as a JSON string because SQLite has no array type. Firestore
 * has one, so the document carries `momDayIndices` as a real array of integers and the
 * conversion lives here: a JSON blob on the wire is opaque to a security rule and to anyone
 * reading the console.
 *
 * **This collection is read by document id only** — there is no list query here and there must
 * not be one. `CLAUDE.md` item 12 is about the opposite mistake (a list query that fails to
 * mirror the field its rule keys on), and the reflex it breeds is to add a
 * `whereArrayContains("participants", uid)` that nothing needs. `firestore.rules` grants
 * `allow get`, deliberately not `allow read`, so such a query would be rejected outright rather
 * than quietly working.
 *
 * Two other constraints of that rule shape every write below, and neither is optional:
 * `participants` must be stored pre-sorted (`participants[0] < participants[1]` is enforced on
 * create) and is compared with order-sensitive equality on update, so every write sends the
 * same sorted array; and a non-merge `set()` that omitted `participants` would be denied as an
 * evaluation error.
 */
@Singleton
class FirestoreCustodyDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val custodyCollection = firestore.collection(COLLECTION)

    /**
     * Observes the pair's custody document, emitting `null` while it does not exist.
     *
     * The flow *fails* when the listener reports an error — a denial, or an offline device with
     * a cold cache — rather than swallowing it. Containment belongs to the caller, which retries
     * with backoff; a data source that hid the failure would take that choice away.
     *
     * @param documentId The pair's custody id, from `CustodyKey.of`.
     */
    fun observeCustody(documentId: String): Flow<SharedCustody?> = callbackFlow {
        val subscription = custodyCollection
            .document(documentId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.data?.toSharedCustody(documentId))
            }

        awaitClose { subscription.remove() }
    }

    /**
     * Fetches the pair's custody document once, or `null` if there is none.
     *
     * The one-shot counterpart of [observeCustody], for the callers a stream cannot serve:
     * reading the co-parent's pattern at the moment pairing is accepted, and reading back
     * `createdAt` before an update so the arrangement is not re-dated.
     *
     * @param documentId The pair's custody id, from `CustodyKey.of`.
     */
    suspend fun getCustody(documentId: String): SharedCustody? {
        val snapshot = custodyCollection.document(documentId).get().await()
        return snapshot.data?.toSharedCustody(documentId)
    }

    /**
     * Writes the pair's custody document, replacing whatever was there.
     *
     * [participants] is sorted here rather than trusted from the caller: the stored array must
     * be in ascending order for the document to be creatable at all, and must keep that exact
     * order for every later update to pass. Sorting at the single point of write makes that
     * true by construction instead of by convention.
     *
     * @param documentId The pair's custody id, from `CustodyKey.of` — which sorts the same two
     *   uids the same way, so the id and [participants] cannot disagree.
     * @param participants The two parents' Firebase UIDs, in any order.
     * @param custody The pattern and the document's own metadata.
     */
    suspend fun setCustody(
        documentId: String,
        participants: List<String>,
        custody: SharedCustody
    ) {
        custodyCollection.document(documentId)
            .set(custody.toDocument(participants.sorted()))
            .await()
    }

    /**
     * The document as this app writes it. Dates are ISO strings, as everywhere else in this
     * schema; `momDayIndices` is a real array (see the class KDoc).
     */
    private fun SharedCustody.toDocument(sortedParticipants: List<String>): Map<String, Any> =
        mapOf(
            "id" to model.id,
            "participants" to sortedParticipants,
            "lastModifiedBy" to lastModifiedBy,
            "modelType" to CustodyModelType.toString(model.modelType),
            "patternDays" to model.patternDays,
            "momDayIndices" to model.momDayIndices.sorted(),
            "startDate" to model.startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "repeatYearly" to repeatYearly,
            "createdAt" to createdAt,
            "lastModifiedAt" to lastModifiedAt
        )

    /**
     * The document as it comes back, or `null` when it cannot describe a pattern at all.
     *
     * Every number crossing Firestore arrives as a [Long], never as an [Int]: the wire format
     * has one integer type. Each one is therefore narrowed through [Number] rather than cast —
     * a `ClassCastException` raised inside a snapshot listener is not a failure the caller's
     * `retryWhen` can see, it is a crash.
     *
     * A document missing the two fields a pattern cannot be reconstructed without is treated as
     * absent rather than half-parsed into a schedule that would assign the wrong days.
     *
     * @param documentId Falls back as the model id for a document written without one, so a
     *   stray field omission cannot make the whole schedule unreadable.
     */
    private fun Map<String, Any>.toSharedCustody(documentId: String): SharedCustody? {
        val startDate = (this["startDate"] as? String)?.let { iso ->
            runCatching { LocalDate.parse(iso) }.getOrNull()
        }
        val patternDays = (this["patternDays"] as? Number)?.toInt()
        if (startDate == null || patternDays == null) return null

        return SharedCustody(
            model = CustodyModel(
                id = (this["id"] as? String)?.takeIf { it.isNotBlank() } ?: documentId,
                modelType = CustodyModelType.fromString((this["modelType"] as? String).orEmpty()),
                patternDays = patternDays,
                momDayIndices = (this["momDayIndices"] as? List<*>)
                    .orEmpty()
                    .mapNotNull { (it as? Number)?.toInt() }
                    .toSet(),
                startDate = startDate,
                isActive = true
            ),
            lastModifiedBy = (this["lastModifiedBy"] as? String).orEmpty(),
            lastModifiedAt = (this["lastModifiedAt"] as? String).orEmpty(),
            createdAt = (this["createdAt"] as? String).orEmpty(),
            repeatYearly = this["repeatYearly"] as? Boolean ?: true
        )
    }

    private companion object {
        const val COLLECTION = "custody_models"
    }
}
