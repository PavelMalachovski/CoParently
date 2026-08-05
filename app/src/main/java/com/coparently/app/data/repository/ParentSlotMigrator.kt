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
 * Scoped to rows this user created, so it can never touch the co-parent's records if it is
 * ever run on a device that already has both parents' data. Idempotent by construction: the
 * second run matches nothing, because the first left no rows in the old slot.
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
