package com.coparently.app.data.repository

import androidx.room.withTransaction
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.EventDao
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
 */
@Singleton
class ParentSlotMigrator @Inject constructor(
    private val database: CoPlanlyDatabase,
    private val eventDao: EventDao,
    private val custodyModelRepository: CustodyModelRepository
) {
    /**
     * Re-stamps this user's rows from [from] to [to].
     *
     * @param myUid Firebase UID of the signed-in user; must not be blank, or the scoping
     *   clause would match every row in the table including the co-parent's.
     * @return How many rows changed, across all tables touched.
     */
    suspend fun reslot(from: String, to: String, myUid: String): Int {
        require(myUid.isNotBlank()) { "reslot needs a uid to scope by" }
        if (from == to) return 0
        return database.withTransaction {
            eventDao.reslotOwner(from, to, myUid) + eventDao.reslotPickup(from, to, myUid)
        }
    }

    /**
     * Reacts to this user's own profile landing in Room with a different parent slot than the
     * row already there — the shape a server-side slot backfill takes, not a UI action this
     * device performed.
     *
     * [reslot] had exactly one caller: `PairingViewModel.withSlotReslot`, on the
     * accept-invitation path, which reads the slot before and after its own network call. A
     * slot can now also flip behind `SyncService`'s periodic download, when a backfill
     * re-assigns it for a pair that accepted long before this device ever ran that sync —
     * nothing about that path resembles "the user tapped Accept", so it gets a second entry
     * point rather than a second caller of the first one.
     *
     * @param myUid Firebase UID of the signed-in user; forwarded to [reslot] unchanged.
     * @param previousRole The role Room held for [myUid] immediately before the profile that
     *   was just written. Null on a fresh install, where there is no previous row to compare
     *   against — the caller is expected to have captured this *before* writing the new value,
     *   not to re-read it afterwards, or "before" and "after" would read identically.
     * @param newRole The role the profile that was just written carries.
     *
     * Both values must be present, non-blank, and different from each other, or nothing runs.
     * A fresh install reports [previousRole] as null: there is no history to mis-attribute, and
     * reading `null -> "dad"` as a flip would complement a custody model that just arrived,
     * correctly, from the co-parent's shared document, inverting it for no reason.
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
    suspend fun reslotIfSlotChanged(myUid: String, previousRole: String?, newRole: String?) {
        val from = previousRole?.takeIf { it.isNotBlank() }
        val to = newRole?.takeIf { it.isNotBlank() }
        if (from == null || to == null || from == to) return

        reslot(from = from, to = to, myUid = myUid)
        custodyModelRepository.getActiveModelSync()?.complemented()?.let { complemented ->
            custodyModelRepository.saveReslotted(complemented)
        }
    }
}
