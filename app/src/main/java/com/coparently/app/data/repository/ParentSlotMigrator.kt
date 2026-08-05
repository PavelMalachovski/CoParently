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
    private val eventDao: EventDao
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
}
