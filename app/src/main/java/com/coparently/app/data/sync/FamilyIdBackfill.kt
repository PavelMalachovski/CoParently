package com.coparently.app.data.sync

import android.util.Log
import com.coparently.app.data.local.dao.BudgetDao
import com.coparently.app.data.local.dao.ChangeRequestDao
import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.PetDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.domain.family.FamilyKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Names the co-parenting relationship on local rows that were written before they could.
 *
 * Every shared record carries a [FamilyKey] id (docs/DESIGN-multi-family.md, M-2), stamped at
 * create. Two populations of rows have none: everything written before the column existed, and
 * everything written before its owner paired — for which null is the honest value, not a gap
 * (see `DatabaseMigrations.MIGRATION_29_30`). This turns the second kind into the first the
 * moment there *is* a relationship to name.
 *
 * **A device knows of exactly one relationship**, which is what makes an unstamped row
 * unambiguous. When that stops being true (M-4, the family switcher) this becomes a per-family
 * question with no local answer, and by then there will be nothing left for it to do — every row
 * either carries a stamp from its own creation or was stamped here.
 *
 * Local only, and deliberately: it does not clear `syncedToFirestore`, so nothing is re-uploaded
 * for it. The remote copy picks the field up on the record's next ordinary write, and for events
 * and child info on the audience re-queue that runs in the same sync pass. Re-queuing every table
 * would put a co-parent's own downloaded rows into this device's outbox, where the create rule
 * (`createdByFirebaseUid == auth.uid`) rejects them forever. Stamping the documents themselves
 * belongs server-side, in one admin pass over a pair — recorded as M-4 work in the design doc,
 * and not needed before then because nothing reads the remote field yet.
 *
 * Three rules copied from [SyncService]'s audience backfills rather than simplified, each for a
 * failure those two already had:
 *
 * - The marker stores the **partner's UID**, never a boolean. A boolean never re-arms when the
 *   same two people pair again after an unpair.
 * - When unpaired the marker is **blanked**, not left naming an ex-partner, or re-pairing with
 *   that same person finds it already equal and skips the backfill.
 * - It is scoped **per user**, because Room's rows survive sign-out and a second account on the
 *   same device must not read the first account's history as its own.
 */
@Singleton
// Six DAOs and a preference store: the six tables *are* the job, so the list is the scope of
// this class stated once rather than a signature anybody types. Taking the database instead
// would trade that for a coupling the tests would then have to mock around.
@Suppress("LongParameterList")
class FamilyIdBackfill @Inject constructor(
    private val eventDao: EventDao,
    private val expenseDao: ExpenseDao,
    private val budgetDao: BudgetDao,
    private val childInfoDao: ChildInfoDao,
    private val petDao: PetDao,
    private val changeRequestDao: ChangeRequestDao,
    private val encryptedPreferences: EncryptedPreferences
) {

    /**
     * Stamps every unstamped local row with [userId]'s family, once per co-parent.
     *
     * @param userId The signed-in user's Firebase UID.
     * @param partnerId The co-parent's Firebase UID, or null when unpaired.
     */
    suspend fun run(userId: String, partnerId: String?) {
        val key = "${PreferenceKeys.FAMILY_ID_BACKFILL_PREFIX}$userId"
        val familyId = FamilyKey.orNull(userId, partnerId)

        // The `partnerId` half is redundant at runtime — a non-null family id implies a non-null
        // partner — but the compiler cannot see that through [FamilyKey.orNull], and the marker
        // below stores the *uid*, not the id. Both halves stay.
        if (familyId == null || partnerId == null) {
            // Blank rather than a removed key: `EncryptedPreferences` has no generic remove, and
            // a blank value can never equal a real UID, so it re-arms exactly as an absent one
            // does. Writing it only when it is not already blank keeps an unpaired account from
            // re-encrypting the same value on every sync tick.
            if (!encryptedPreferences.getString(key).isNullOrBlank()) {
                encryptedPreferences.putString(key, "")
            }
            return
        }
        if (encryptedPreferences.getString(key) == partnerId) return

        val stamped = eventDao.stampFamilyId(familyId) +
            expenseDao.stampFamilyId(familyId) +
            budgetDao.stampFamilyId(familyId) +
            childInfoDao.stampFamilyId(familyId) +
            petDao.stampFamilyId(familyId) +
            changeRequestDao.stampFamilyId(familyId)

        // The marker is written only after the six statements have run. A crash partway through
        // leaves it unwritten and the whole pass repeats, which is safe because every statement
        // is `WHERE familyId IS NULL` — the rows already done match nothing the second time.
        encryptedPreferences.putString(key, partnerId)
        Log.i(TAG, "Family id backfill for $userId: stamped $stamped row(s) as $familyId")
    }

    private companion object {
        const val TAG = "FamilyIdBackfill"
    }
}
