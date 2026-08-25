package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.custody.CustodyDecision
import com.coparently.app.domain.custody.CustodyDecisionOutcome
import com.coparently.app.domain.custody.CustodyProposal
import com.coparently.app.domain.custody.CustodyWriteKind
import com.coparently.app.domain.custody.DayOverride
import com.coparently.app.domain.custody.DayOverrideStatus
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
        buildMap {
            put("id", model.id)
            put("participants", sortedParticipants)
            put("lastModifiedBy", lastModifiedBy)
            put("modelType", CustodyModelType.toString(model.modelType))
            put("patternDays", model.patternDays)
            put("momDayIndices", model.momDayIndices.sorted())
            put("startDate", model.startDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
            put("repeatYearly", repeatYearly)
            put("createdAt", createdAt)
            put("lastModifiedAt", lastModifiedAt)
            // Omitted rather than written as an explicit null: `set()` replaces the whole
            // document, so leaving the key out is what actually clears a withdrawn or answered
            // proposal, and a stored null would read back as a field that exists but says
            // nothing.
            proposal?.let { put("proposal", it.toMap()) }
            lastDecision?.let { put("lastDecision", it.toMap()) }
            // Same omit-rather-than-null rule as the two above, and for the same reason: this is
            // a whole-document `set()`, so leaving the key out is what clears the last swap.
            if (dayOverrides.isNotEmpty()) {
                put("dayOverrides", dayOverrides.mapValues { (_, o) -> o.toMap() })
            }
            // Required by `firestore.rules` on any write that changes `dayOverrides`: Rules
            // cannot iterate a map, so the write names the one date it touches and the rule
            // checks the diff affects only that key.
            lastSwapDate?.let { put("lastSwapDate", it) }
            put("lastModifiedKind", lastModifiedKind.name)
        }

    /**
     * One day's swap as a sub-map. `note` and the two decision fields are omitted when absent
     * rather than written as null, matching every other sub-map in this document.
     */
    private fun DayOverride.toMap(): Map<String, Any> = buildMap {
        put("toParent", toParent)
        put("requestedBy", requestedBy)
        put("requestedAt", requestedAt)
        put("status", status.name)
        decidedBy?.let { put("decidedBy", it) }
        decidedAt?.let { put("decidedAt", it) }
        note?.let { put("note", it) }
        // Omitted when absent, like every other optional key here. A co-parent on an older build
        // simply does not see it and reads each day as its own offer, which is what it was.
        groupId?.let { put("groupId", it) }
    }

    /** The proposal as a sub-map; `momDayIndices` is a real array, like the pattern's. */
    private fun CustodyProposal.toMap(): Map<String, Any> = mapOf(
        "modelType" to CustodyModelType.toString(model.modelType),
        "patternDays" to model.patternDays,
        "momDayIndices" to model.momDayIndices.sorted(),
        "startDate" to model.startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
        "repeatYearly" to repeatYearly,
        "proposedBy" to proposedBy,
        "proposedAt" to proposedAt
    )

    /** The decision as a sub-map. `note` is omitted when absent rather than written as null. */
    private fun CustodyDecision.toMap(): Map<String, Any> = buildMap {
        put("outcome", outcome.name)
        put("by", by)
        put("at", at)
        put("proposalAt", proposalAt)
        note?.let { put("note", it) }
    }

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
            repeatYearly = this["repeatYearly"] as? Boolean ?: true,
            proposal = (this["proposal"] as? Map<*, *>)?.toProposal(documentId),
            lastDecision = (this["lastDecision"] as? Map<*, *>)?.toDecision(),
            dayOverrides = (this["dayOverrides"] as? Map<*, *>).toDayOverrides(),
            lastSwapDate = (this["lastSwapDate"] as? String)?.takeIf { it.isNotBlank() },
            // A document written before this field existed only ever carried pattern writes, so
            // PATTERN is the honest default rather than a convenient one. An unrecognised value
            // from a newer build lands there too: announcing a change that did happen is the
            // safe side of that error, and silence is the side this product cannot afford.
            lastModifiedKind = (this["lastModifiedKind"] as? String)
                ?.let { name -> CustodyWriteKind.entries.firstOrNull { it.name == name } }
                ?: CustodyWriteKind.PATTERN
        )
    }

    /**
     * The one-off swaps, keyed by ISO date. Absent reads as an empty map, never null.
     *
     * An entry that cannot describe a swap is **dropped**, not defaulted — the same rule the
     * proposal sub-map follows, and it matters more here: a half-parsed override silently moves a
     * child to the wrong parent on the calendar both parents plan around. An unrecognised status
     * from a newer build is dropped for the same reason, rather than being read as ACCEPTED.
     */
    private fun Map<*, *>?.toDayOverrides(): Map<String, DayOverride> =
        this.orEmpty().entries.mapNotNull { (key, value) ->
            val date = (key as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val entry = (value as? Map<*, *>)?.toDayOverride() ?: return@mapNotNull null
            date to entry
        }.toMap()

    /** One swap sub-map, or null when it cannot describe a swap this build understands. */
    private fun Map<*, *>.toDayOverride(): DayOverride? {
        val toParent = (this["toParent"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val requestedBy = (this["requestedBy"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val status = (this["status"] as? String)
            ?.let { name -> DayOverrideStatus.entries.firstOrNull { it.name == name } }
            ?: return null

        return DayOverride(
            toParent = toParent,
            requestedBy = requestedBy,
            requestedAt = (this["requestedAt"] as? String).orEmpty(),
            status = status,
            decidedBy = (this["decidedBy"] as? String)?.takeIf { it.isNotBlank() },
            decidedAt = (this["decidedAt"] as? String)?.takeIf { it.isNotBlank() },
            note = (this["note"] as? String)?.takeIf { it.isNotBlank() },
            groupId = (this["groupId"] as? String)?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * The proposal sub-map, or null when it cannot describe a pattern.
     *
     * Same rule as the agreed pattern above: a sub-map missing `startDate` or `patternDays` is
     * treated as no proposal rather than half-parsed into a schedule that would put the child
     * with the wrong parent — and unlike the agreed pattern, this one is about to be shown to a
     * parent as something to say yes to. `proposedBy` joins them because a proposal nobody is
     * named on cannot be decided: `CustodyProposalTransition` refuses a decision from the
     * proposer, and a blank author would let either parent accept their own request.
     *
     * Numbers are narrowed through [Number], never cast: Firestore's wire format has one integer
     * type, and a `ClassCastException` raised inside a snapshot listener is not a failure the
     * caller's `retryWhen` can see, it is a crash.
     *
     * @param documentId Used as the proposed model's id — the sub-map carries no id of its own,
     *   and the pair's document is the only identity a pending proposal has.
     */
    private fun Map<*, *>.toProposal(documentId: String): CustodyProposal? {
        val startDate = (this["startDate"] as? String)?.let { iso ->
            runCatching { LocalDate.parse(iso) }.getOrNull()
        }
        val patternDays = (this["patternDays"] as? Number)?.toInt()
        val proposedBy = (this["proposedBy"] as? String)?.takeIf { it.isNotBlank() }
        if (startDate == null || patternDays == null || proposedBy == null) return null

        return CustodyProposal(
            model = CustodyModel(
                id = documentId,
                modelType = CustodyModelType.fromString((this["modelType"] as? String).orEmpty()),
                patternDays = patternDays,
                momDayIndices = (this["momDayIndices"] as? List<*>)
                    .orEmpty()
                    .mapNotNull { (it as? Number)?.toInt() }
                    .toSet(),
                startDate = startDate,
                // Not the active pattern, and must never be mistaken for one by a caller that
                // reads the field to decide what the calendar should colour.
                isActive = false
            ),
            repeatYearly = this["repeatYearly"] as? Boolean ?: true,
            proposedBy = proposedBy,
            proposedAt = (this["proposedAt"] as? String).orEmpty()
        )
    }

    /**
     * The decision sub-map, or null when its outcome is not one this build knows.
     *
     * An unrecognised outcome is dropped rather than defaulted: a co-parent on a newer build
     * inventing a third outcome must not have it silently read as ACCEPTED, which would tell
     * this parent their schedule changed when it did not.
     */
    private fun Map<*, *>.toDecision(): CustodyDecision? {
        val outcome = (this["outcome"] as? String)
            ?.let { name -> CustodyDecisionOutcome.entries.firstOrNull { it.name == name } }
            ?: return null

        return CustodyDecision(
            outcome = outcome,
            by = (this["by"] as? String).orEmpty(),
            at = (this["at"] as? String).orEmpty(),
            proposalAt = (this["proposalAt"] as? String).orEmpty(),
            note = (this["note"] as? String)?.takeIf { it.isNotBlank() }
        )
    }

    private companion object {
        const val COLLECTION = "custody_models"
    }
}
