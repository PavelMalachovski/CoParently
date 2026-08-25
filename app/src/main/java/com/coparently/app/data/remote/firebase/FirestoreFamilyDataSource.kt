package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data source for `families/{familyId}` — the document that says who co-parents with whom.
 *
 * The collection is written almost entirely by Cloud Functions, as admin: `members` and `slots`
 * are grants, and a client that could write either would name itself into a stranger's family or
 * take the co-parent's slot. `firestore.rules` refuses a client create and delete outright and
 * allows exactly one update — the caller's own `caresFor` entry — which is why this class has one
 * write method and nothing else.
 *
 * See docs/DESIGN-multi-family.md, M-3.
 */
@Singleton
class FirestoreFamilyDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val familiesCollection = firestore.collection("families")

    /**
     * Records [uid]'s own answer to "children, pets, or both" on their family.
     *
     * `FieldPath.of("caresFor", uid)` rather than a dotted `"caresFor.$uid"` string: the dotted
     * form is *parsed* as a path, so a uid carrying a dot would silently write somewhere else.
     * Firebase uids never do, which is exactly the kind of assumption worth not making in the
     * one write a client is allowed to make on this collection.
     *
     * An `update` and never a `set`: the rule permits only an update, and a `set` on a family
     * that does not exist yet would create one carrying `caresFor` and no `members` — and the
     * read rule keys on `members`, where a missing key is an evaluation error and therefore a
     * denial, leaving a document neither parent could ever read.
     *
     * Throws rather than returning a `Result`, matching the other data sources here — the
     * caller decides what a failure means, and the ordinary one is not a defect: a pair whose
     * `families/{id}` does not exist yet, because they paired before it was introduced and
     * `backfillFamilyDocuments` has not run.
     *
     * @param familyId `FamilyKey.of(myUid, partnerUid)`.
     * @param uid The signed-in user; the rule refuses any other key.
     * @param stored The kinds in `FamilyKind.toStored` form, or `""` for "has not said".
     */
    suspend fun setCaresFor(familyId: String, uid: String, stored: String) {
        familiesCollection.document(familyId)
            .update(FieldPath.of("caresFor", uid), stored)
            .await()
    }
}
