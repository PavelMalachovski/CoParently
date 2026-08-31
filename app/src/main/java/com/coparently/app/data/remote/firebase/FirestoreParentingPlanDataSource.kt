package com.coparently.app.data.remote.firebase

import android.util.Log
import com.coparently.app.domain.parentingplan.ParentingPlanEntry
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared half of a family's parenting plan (MON-5).
 *
 * **One document per family, four maps, every one keyed by uid.** That shape is not a storage
 * convenience — it is what lets `firestore.rules` say "a parent writes their own key and nothing
 * else" in a way the server enforces. A document per parent would have needed a query to read
 * both halves, and a query cannot be constrained by a rule that keys on the document id.
 *
 * The id is `FamilyKey.of(myUid, partnerUid)`, so it is the same string `custody_models`,
 * `family_settings` and `conversations` are already named with, and no lookup is needed to find it.
 */
@Singleton
class FirestoreParentingPlanDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    /**
     * Both halves of [familyId]'s plan, live, keyed by the uid that wrote each.
     *
     * Emits an empty map for a document that does not exist, which is every pair's first read —
     * `firestore.rules` permits it for exactly that reason. A failure ends the flow rather than
     * throwing into the collector's scope; the caller merges this with Room and keeps working.
     */
    fun observePlan(familyId: String): Flow<Map<String, ParentingPlanEntry>> = callbackFlow {
        val registration = firestore.collection(COLLECTION).document(familyId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Parenting plan listener failed for $familyId", error)
                    close(error)
                    return@addSnapshotListener
                }
                trySend(halvesOf(snapshot?.data.orEmpty()))
            }
        awaitClose { registration.remove() }
    }

    /**
     * Writes [entry] as [uid]'s half, leaving the co-parent's untouched.
     *
     * `set` rather than `update`, because the document does not exist until one of the two
     * parents writes something and `update` fails on a missing one.
     *
     * **`mergeFieldPaths`, not `merge()`, and the difference is a lost deletion.** A plain merge
     * recurses into nested maps and never removes a key that the new data omits — so a parent who
     * cleared an answer would have it disappear from their own screen and stay on the co-parent's
     * forever, which is the worst shape this feature could fail in: a wording somebody has
     * withdrawn still standing in a document the two of them may hand to a court. Naming the four
     * paths replaces each of them wholesale and touches nothing else, so a removal inside this
     * parent's own map propagates and the co-parent's map is still never written.
     */
    suspend fun uploadHalf(familyId: String, uid: String, entry: ParentingPlanEntry) {
        firestore.collection(COLLECTION).document(familyId).set(
            mapOf(
                FIELD_ANSWERS to mapOf(uid to entry.answers),
                FIELD_AGREED_TO to mapOf(uid to entry.agreedTo),
                FIELD_CATALOGUE_VERSIONS to mapOf(uid to entry.catalogueVersion),
                FIELD_UPDATED_AT to mapOf(uid to entry.updatedAtMillis)
            ),
            SetOptions.mergeFieldPaths(
                listOf(
                    FieldPath.of(FIELD_ANSWERS, uid),
                    FieldPath.of(FIELD_AGREED_TO, uid),
                    FieldPath.of(FIELD_CATALOGUE_VERSIONS, uid),
                    FieldPath.of(FIELD_UPDATED_AT, uid)
                )
            )
        ).await()
    }

    /**
     * Turns one document into an entry per author.
     *
     * Every field is read through its own map because a half-written plan legitimately omits
     * some: a parent who has answered nothing but ticked nothing writes no `agreedTo`. The union
     * of the uids seen across the four maps is what decides who has a half at all, so a parent
     * who has only ever ticked still appears.
     */
    private fun halvesOf(data: Map<String, Any?>): Map<String, ParentingPlanEntry> {
        val answers = nestedText(data[FIELD_ANSWERS])
        val agreedTo = nestedText(data[FIELD_AGREED_TO])
        val versions = data[FIELD_CATALOGUE_VERSIONS] as? Map<*, *>
        val updatedAt = data[FIELD_UPDATED_AT] as? Map<*, *>
        val authors: Set<String> = answers.keys + agreedTo.keys +
            versions?.keys?.filterIsInstance<String>().orEmpty() +
            updatedAt?.keys?.filterIsInstance<String>().orEmpty()
        return authors.associateWith { uid ->
            ParentingPlanEntry(
                answers = answers[uid].orEmpty(),
                agreedTo = agreedTo[uid].orEmpty(),
                catalogueVersion = (versions?.get(uid) as? Number)?.toInt() ?: 0,
                updatedAtMillis = (updatedAt?.get(uid) as? Number)?.toLong() ?: 0L
            )
        }
    }

    /**
     * A `{uid: {questionId: text}}` field, with anything that is not that shape dropped.
     *
     * Written as two flat loops rather than nested `mapNotNull`s: the inner one would need
     * `return@mapNotNull` inside another `mapNotNull`, where the label binds to whichever is
     * nearer and a later edit can silently move it.
     */
    private fun nestedText(value: Any?): Map<String, Map<String, String>> {
        val outer = value as? Map<*, *> ?: return emptyMap()
        val halves = mutableMapOf<String, Map<String, String>>()
        outer.forEach { (uid, inner) -> if (uid is String) halves[uid] = textMap(inner) }
        return halves
    }

    /** One parent's `{questionId: text}` map, with anything that is not text dropped. */
    private fun textMap(value: Any?): Map<String, String> {
        val inner = value as? Map<*, *> ?: return emptyMap()
        val texts = mutableMapOf<String, String>()
        inner.forEach { (question, text) -> if (question is String && text is String) texts[question] = text }
        return texts
    }

    companion object {
        private const val TAG = "ParentingPlanSource"
        private const val COLLECTION = "parenting_plans"

        /** The four field names. They are the stored schema and are never renamed. */
        private const val FIELD_ANSWERS = "answers"
        private const val FIELD_AGREED_TO = "agreedTo"
        private const val FIELD_CATALOGUE_VERSIONS = "catalogueVersions"
        private const val FIELD_UPDATED_AT = "updatedAt"
    }
}
