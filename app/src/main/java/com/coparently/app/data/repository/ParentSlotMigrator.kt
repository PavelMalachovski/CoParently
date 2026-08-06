package com.coparently.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves this device's records from one parent slot to the other.
 *
 * An unpaired parent occupies slot 1 and everything they create is stamped with it. Accepting
 * an invitation moves them to slot 2, at which point their own past records would read as the
 * co-parent's. This re-stamps them.
 *
 * Both queries are scoped to `createdByFirebaseUid = myUid`, so neither can *select* a row
 * this user did not create. That alone guarantees [EventDao.reslotOwner] can never touch the
 * co-parent's records: it rewrites `parentOwner`, the same "whose event is this" concept the
 * scoping clause already keys on. It does **not**, by itself, make the identical claim true of
 * [EventDao.reslotPickup]: that query rewrites `pickupConfirmedBy`, which records who
 * confirmed the pickup — a different person's action, potentially the co-parent's, on an
 * event this user created. What actually makes today's call site safe is that this migrator
 * runs exactly once, immediately after this device's own slot changes, before any co-parent
 * could plausibly have confirmed a pickup on rows created before that moment — not a
 * structural guarantee of the query itself. A future caller re-running this once both parents
 * already share history would need to re-examine that.
 *
 * Idempotent by construction: the second run matches nothing, because the first left no rows
 * in the old slot.
 *
 * Both statements also clear `syncedToFirestore` on the rows they touch, which is what carries
 * the re-stamp off this device. Without it the next `SyncService.performFullSync` skipped these
 * rows in its upload half and then overwrote them from the still-stale documents in its download
 * half — in the same pass, one step after this class had run, and permanently, because the slot
 * marker below had already advanced. See [EventDao.reslotOwner].
 *
 * [reslot] also records, in [EncryptedPreferences], which slot this user's records are now
 * stamped with — see [reslotIfSlotChanged] for why that record exists and why it is not
 * [com.coparently.app.domain.model.User.role].
 */
@Singleton
class ParentSlotMigrator @Inject constructor(
    private val database: CoPlanlyDatabase,
    private val eventDao: EventDao,
    private val custodyModelRepository: CustodyModelRepository,
    private val encryptedPreferences: EncryptedPreferences
) {
    /**
     * Re-stamps this user's rows from [from] to [to].
     *
     * Also records [to] as [myUid]'s current slot in [EncryptedPreferences] — see
     * [reslotIfSlotChanged] — so this, the one place this device's records actually change
     * slot, is the one place that record is kept current. Both of today's callers
     * (`PairingViewModel.withSlotReslot` on the accept path, and [reslotIfSlotChanged] on the
     * periodic-sync path) get the record for free by going through here rather than having to
     * remember to keep it current themselves.
     *
     * @param myUid Firebase UID of the signed-in user; must not be blank, or the scoping
     *   clause would match every row in the table including the co-parent's.
     * @return How many rows changed, across all tables touched.
     */
    suspend fun reslot(from: String, to: String, myUid: String): Int {
        require(myUid.isNotBlank()) { "reslot needs a uid to scope by" }
        if (from == to) return 0
        val changed = database.withTransaction {
            eventDao.reslotOwner(from, to, myUid) + eventDao.reslotPickup(from, to, myUid)
        }
        encryptedPreferences.putString(slotMarkerKey(myUid), to)
        return changed
    }

    /**
     * The slot this device last stamped [myUid]'s records with, or null if it never has.
     *
     * The same marker [reslotIfSlotChanged] compares against, exposed because
     * `PairingViewModel.withSlotReslot` needs exactly the same "before" and had been deriving it
     * from `User.role` — the field this class documents, at length, as unusable for that. A
     * wrong `from` re-stamps nothing (it matches no row), reports zero, and then complements a
     * pattern that may already be complemented; it self-heals only if a later detector pass
     * re-stamps, and after [reslot] has advanced the marker no later pass ever will.
     *
     * Null is a real answer and not an error: it means this device has never moved [myUid]
     * between slots, which is the ordinary state of an unpaired account. The caller falls back
     * to the profile's `role` there, because with no marker there is nothing better and the
     * placeholder is at least what new records were stamped with.
     */
    fun knownSlot(myUid: String): String? =
        encryptedPreferences.getString(slotMarkerKey(myUid))

    /**
     * Reacts to this user's own profile arriving with a different parent slot than the one
     * this device last knew about — the shape a server-side slot backfill takes, not a UI
     * action this device performed.
     *
     * [reslot] had exactly one caller: `PairingViewModel.withSlotReslot`, on the
     * accept-invitation path, which reads the slot before and after its own network call. A
     * slot can now also flip behind `SyncService`'s periodic download, when a backfill
     * re-assigns it for a pair that accepted long before this device ever ran that sync —
     * nothing about that path resembles "the user tapped Accept", so it gets a second entry
     * point rather than a second caller of the first one.
     *
     * **The "before" side is [EncryptedPreferences]'s marker, never `User.role`.** An earlier
     * version of this method compared against Room's `role` field and had three separate,
     * related defects:
     *
     * 1. `role` is never written by the accept path (`PairingRepositoryImpl` only writes
     *    `partnerId`; see `PairingViewModel.withSlotReslot`'s own KDoc). So after an Accept that
     *    changes the slot, Room's `role` still says the *old* slot for up to fifteen minutes,
     *    until the next sync happens to overwrite it — and when that sync ran the old
     *    role-based comparison, it read the accept as a second, brand-new transition and
     *    complemented an already-correct custody model a second time, inverting it.
     * 2. `role` is non-nullable and seeded with a placeholder (`DEFAULT_ROLE`, `"mom"`) the
     *    moment a local row is first created, whether or not the real value was ever read
     *    successfully — so a "no previous value" guard keyed on `role == null` can never fire
     *    in production, even though the scenario it exists for (a fresh install whose first
     *    profile read is a placeholder) is real.
     * 3. The `role` write and the re-stamp were two separate, non-atomic steps. A process death
     *    or a cancelled `SyncWorker` between them left Room holding the new slot with every
     *    record still stamped with the old one, permanently: the next comparison found `role`
     *    already equal to the incoming value and never retried.
     *
     * A marker closes all three, and it is [reslot] — not this method — that advances it, right
     * after its own transaction commits and **before** the custody complement below runs. That
     * ordering is deliberate, not incidental: it is what lets `PairingViewModel.withSlotReslot`
     * — which calls [reslot] directly and never this method — advance the same marker for free,
     * with no change to that class at all. It does not exist at all until a slot has genuinely
     * been re-stamped once, so a fresh install's placeholder cannot be mistaken for a change; it
     * is already advanced by the time a sync follows an Accept that changed the slot, so that
     * sync sees no change to react to; and a run interrupted before [reslot] returns leaves the
     * marker stale, so the next sync retries the *re-stamp* rather than silently treating the
     * interrupted attempt as done.
     *
     * **Residual, disclosed rather than hidden, and it is not a small one.** Because the marker
     * advances inside [reslot] and the complement runs after it, a process death in the window
     * between the two leaves the marker already at its new value while the complement never ran
     * — and the next sync, seeing no change, does not retry it. Do not read "a skipped
     * complement" as a deferred reconciliation: `momDayIndices` means "the days slot 1 has
     * custody", so a pattern left un-complemented after this device moved to slot 2 hands the
     * user's own custody days to their co-parent, on their own calendar, permanently, with no
     * banner and no error. It is the same outcome the ordering in
     * `PairingViewModel.reconcileCustody` exists to prevent on the accept path.
     *
     * It is nonetheless the better of the two available orderings, and the reason is that
     * [com.coparently.app.domain.model.CustodyModel.complemented] is **not idempotent**:
     * applying it twice restores the original, so a retry that re-complements is an inversion of
     * exactly the same severity, in the opposite direction, and it can fire on a device that was
     * never interrupted at all. Tying the marker to the complement's completion — the obvious
     * alternative — buys "retried once" at the price of "possibly applied twice", and there is no
     * transaction that could span both, because the marker lives in [EncryptedPreferences] and
     * the complement in Room. Closing this properly needs the model to record which slot it is
     * expressed in, so that complementing becomes an assertion about state rather than a toggle;
     * that is a Room schema change and a follow-up, not a comment.
     *
     * @param myUid Firebase UID of the signed-in user; forwarded to [reslot] unchanged and used
     *   to scope the marker, so a device where a second account has signed in later (Room's
     *   `users` rows are never cleared on sign-out either) reads its own marker, not the
     *   previous account's.
     * @param newRole The role the profile that was just written carries. Null or blank is
     *   treated as "nothing to react to", the same as an absent marker.
     *
     * A missing marker for [myUid] is not a change: there is nothing to re-stamp against, and
     * complementing the active custody model on the strength of it would invert a pattern that
     * may have just arrived, correctly, from the co-parent's shared document. The marker is
     * seeded with [newRole] instead, so the *next* call has a real baseline.
     *
     * The re-stamp runs before the custody complement, in that order — the same order
     * `PairingViewModel.reconcileCustody` already uses on the accept path, for the same
     * reason: it keeps the two entry points auditable the same way.
     *
     * The complement is a Room-only write, through [CustodyModelRepository.saveReslotted]:
     * it preserves the model's existing `createdAt`/`lastModifiedAt` rather than re-dating it.
     * Re-dating would make this device win every later staleness comparison in
     * [CustodyModelRepository]'s mirror, turning a re-expression of the same arrangement into
     * an overwrite of the co-parent's schedule — the failure mode [CustodyModelRepository]'s
     * own `saveAndActivate` exists to avoid for this exact case. The pair's shared document is
     * never touched here: it is expressed in slot terms and is already correct, because the
     * co-parent's slot did not move.
     *
     * A missing active custody model — an unpaired user, or one who has never set a schedule —
     * is not an error: there is nothing to complement.
     */
    suspend fun reslotIfSlotChanged(myUid: String, newRole: String?) {
        val to = newRole?.takeIf { it.isNotBlank() } ?: return
        val marker = knownSlot(myUid)
        if (marker == null) {
            encryptedPreferences.putString(slotMarkerKey(myUid), to)
            return
        }
        if (marker == to) return

        // Row count logged on success for the same reason `PairingViewModel.reslotIfChanged`
        // logs it: this is a one-shot reaction with nobody watching a screen, so a silent zero
        // would be undetectable in the field — and it is the only symptom an interrupted pass
        // (see the class doc's point 3) leaves behind for an operator to find.
        val changed = reslot(from = marker, to = to, myUid = myUid)
        Log.i(TAG, "Reacted to a remote slot change ($marker -> $to) for $myUid: re-stamped $changed record(s)")

        custodyModelRepository.getActiveModelSync()?.complemented()?.let { complemented ->
            custodyModelRepository.saveReslotted(complemented)
        }
    }

    /** The per-user key this user's slot marker is stored under; see [PreferenceKeys]. */
    private fun slotMarkerKey(myUid: String) = "${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}$myUid"

    private companion object {
        const val TAG = "ParentSlotMigrator"
    }
}
