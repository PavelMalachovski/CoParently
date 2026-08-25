package com.coparently.app.data.family

import android.util.Log
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.family.FamilyKey
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One co-parenting relationship this device can be looking at.
 *
 * @property familyId `FamilyKey.of(myUid, partnerUid)` — the id every shared record carries.
 * @property partnerUid The other adult in it.
 */
data class FamilyOption(
    val familyId: String,
    val partnerUid: String
)

/**
 * Which family this device is currently showing, out of the ones the signed-in parent is in.
 *
 * A person may co-parent with more than one other adult, and the app shows **one at a time** —
 * a switcher rather than a merged view, because the alternative asks questions that have no
 * answer: two families have two independent custody schedules, and "whose day is it" cannot be
 * merged (docs/DESIGN-multi-family.md).
 *
 * **The selection is projected onto `UserEntity.partnerId`, and that is the design, not a
 * shortcut.** Something like a hundred and forty places read that field, and every one of them
 * wants "the co-parent of the family I am showing" — the audience an event is uploaded with,
 * the id a chat thread is derived from, the pair a custody schedule belongs to, the family an
 * expense query filters on. Re-pointing the single field they already read is what makes
 * switching a family a one-row write instead of a hundred and forty call-site changes, and it
 * is why the real list lives beside it in [UserEntity.partnerIdsJson] rather than replacing it.
 *
 * Two rules keep the projection honest:
 *
 * - **It is local only.** `UserRepositoryImpl.updateUser` deliberately stops sending
 *   `partnerId` to Firestore — the field is server-managed by the pairing callables, and
 *   publishing a per-device UI choice into a document the co-parent reads would be a different
 *   kind of bug entirely. The write here goes straight to [UserDao].
 * - **The selection is validated on every read**, never trusted. A stored id survives an
 *   unpair, and a family the account is no longer in must not select itself; an unknown id
 *   falls back to the first real one.
 *
 * Scoped per user for the reason `PreferenceKeys.PARENT_SLOT_MARKER_PREFIX` gives: Room rows
 * survive sign-out, so a second account on the same device must not inherit the first one's
 * choice.
 */
@Singleton
class SelectedFamilySource @Inject constructor(
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val encryptedPreferences: EncryptedPreferences
) {

    /**
     * Reads the stored co-parent list, which is a JSON array of plain uid strings.
     *
     * Room and Firebase Auth directly, rather than `UserRepository`: this is injected into
     * `PairingRepositoryImpl` and `SyncService`, and reaching back through a repository whose
     * own graph reaches those would be a dependency cycle waiting to close. All this needs is
     * one row and one uid.
     */
    private val gson = Gson()

    /**
     * The families the signed-in parent is in, oldest relationship first.
     *
     * Derived from the stored co-parent list rather than from `families/{id}`: this has to work
     * offline, and the id is a pure function of the two uids anyway. Blank and self-referential
     * entries are dropped by [FamilyKey.orNull] rather than becoming a family of one.
     */
    suspend fun families(): List<FamilyOption> {
        val uid = currentUid() ?: return emptyList()
        val me = userDao.getUserById(uid) ?: return emptyList()
        return gson.fromJson(me.partnerIdsJson, Array<String>::class.java)
            ?.toList().orEmpty()
            .mapNotNull { partnerUid ->
                FamilyKey.orNull(uid, partnerUid)?.let { FamilyOption(it, partnerUid) }
            }
    }

    /**
     * The family this device is showing, or null when the account is in none.
     *
     * Falls back to the first when nothing is stored or the stored id names a relationship that
     * has since ended — which is the ordinary case after an unpair, not an error.
     */
    suspend fun selected(): FamilyOption? {
        val options = families()
        if (options.isEmpty()) return null
        val stored = currentUid()?.let { encryptedPreferences.getString(key(it)) }
        return options.firstOrNull { it.familyId == stored } ?: options.first()
    }

    /**
     * Points this device at [familyId].
     *
     * A no-op for a family the account is not in: a stale switcher tap must not blank the
     * co-parent every downstream reader depends on.
     *
     * @return The family now selected, or null when [familyId] named none of them.
     */
    suspend fun select(familyId: String): FamilyOption? {
        val uid = currentUid() ?: return null
        val target = families().firstOrNull { it.familyId == familyId }
        if (target == null) {
            Log.w(TAG, "Ignoring a switch to $familyId, which $uid is not part of")
            return null
        }
        encryptedPreferences.putString(key(uid), familyId)
        applyProjection(uid, target)
        return target
    }

    /**
     * Re-applies the projection without changing the choice.
     *
     * Called after anything that can move the set of families under the selection — a sync that
     * brings a new co-parent down, an unpair, a fresh sign-in. Without it a device that had
     * been showing a family it has just left would keep `partnerId` pointing at the ex-partner
     * until the parent happened to open the switcher.
     */
    suspend fun reconcile() {
        val uid = currentUid() ?: return
        val target = selected()
        if (target == null) {
            applyProjection(uid, null)
            return
        }
        encryptedPreferences.putString(key(uid), target.familyId)
        applyProjection(uid, target)
    }

    /**
     * Emits the selected family whenever the signed-in parent's row changes.
     *
     * Room's own invalidation drives this — the projection is a column on that row — so no
     * Firestore listener is added for it. `distinctUntilChanged` because the row re-emits when
     * any unrelated column moves.
     */
    fun observe(uid: String): Flow<FamilyOption?> =
        userDao.observeUserById(uid)
            .map { row ->
                val partnerUid = row?.partnerId
                FamilyKey.orNull(uid, partnerUid)?.let { FamilyOption(it, partnerUid.orEmpty()) }
            }
            .distinctUntilChanged()

    /** Writes the selection onto the local row, and nowhere else. */
    private suspend fun applyProjection(uid: String, target: FamilyOption?) {
        val row = userDao.getUserById(uid) ?: return
        val partnerId = target?.partnerUid
        if (row.partnerId == partnerId) return
        userDao.updateUser(row.copy(partnerId = partnerId))
    }

    private fun currentUid(): String? = firebaseAuthService.getCurrentUser()?.uid

    private fun key(uid: String) = "${PreferenceKeys.SELECTED_FAMILY_PREFIX}$uid"

    private companion object {
        const val TAG = "SelectedFamily"
    }
}
