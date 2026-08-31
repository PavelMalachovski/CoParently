package com.coparently.app.data.local.entity

import androidx.room.Entity

/**
 * One parent's half of one family's parenting plan (MON-5).
 *
 * **A row per (family, author), not per answer.** Answers are always read and written as a whole
 * half — the screen shows every question, and the Firestore document carries one map per parent —
 * so a row per question would buy nothing and cost a join plus a delete-what-is-missing
 * reconciliation on every save, which is the shape `data/sync/Tombstone.kt` warns about.
 *
 * The signed-in parent's row is theirs to edit and to upload. The co-parent's row is a downloaded
 * mirror and is never written locally: `firestore.rules` refuses a write to the other parent's
 * key, so a row that disagreed with the server could only ever mislead the reader.
 *
 * @property familyId The co-parenting relationship, from `FamilyKey.of` — also the Firestore
 * document id, so the two never have to be reconciled.
 * @property authorUid Which parent wrote this half.
 * @property catalogueVersion `ParentingPlanCatalogue.VERSION` as it was when this half was last
 * saved.
 * @property answersJson JSON object of question id to answer text; `{}` when nothing is answered.
 * @property agreedToJson JSON object of question id to **the co-parent's answer text this parent
 * ticked** — see `ParentingPlanEntry.agreedTo` for why the text and not a flag.
 * @property updatedAtMillis When this half was last edited, epoch millis, for the reason
 * `data/sync/Tombstone.kt` gives: it crosses between two phones that may be in two zones.
 * @property syncedToFirestore Whether this half has been uploaded. Always true on the co-parent's
 * mirrored row, which this device never uploads.
 */
@Entity(tableName = "parenting_plan_entries", primaryKeys = ["familyId", "authorUid"])
data class ParentingPlanEntryEntity(
    val familyId: String,
    val authorUid: String,
    val catalogueVersion: Int,
    val answersJson: String,
    val agreedToJson: String,
    val updatedAtMillis: Long,
    val syncedToFirestore: Boolean
)
