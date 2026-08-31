package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coparently.app.data.local.entity.ParentingPlanEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The two halves of a family's parenting plan (MON-5).
 *
 * Every query is scoped to one `familyId`. That is not tidiness: a device that has been paired
 * more than once holds a row per family, and an unscoped read would put a previous household's
 * answers on the screen — the defect item 18 in `CLAUDE.md` exists to keep out of the other five
 * tables.
 */
@Dao
interface ParentingPlanDao {

    /** Both halves of one family's plan, the signed-in parent's and the co-parent's. */
    @Query("SELECT * FROM parenting_plan_entries WHERE familyId = :familyId")
    fun observeEntries(familyId: String): Flow<List<ParentingPlanEntryEntity>>

    /** One parent's half, for the save path, which reads before it writes. */
    @Query(
        "SELECT * FROM parenting_plan_entries WHERE familyId = :familyId AND authorUid = :authorUid"
    )
    suspend fun getEntry(familyId: String, authorUid: String): ParentingPlanEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ParentingPlanEntryEntity)

    /**
     * The signed-in parent's halves that have not reached Firestore, across every family.
     *
     * The outbox. Not filtered by family on purpose — a sync pass drains everything this account
     * owns, and a half written before the device knew its family id would otherwise sit forever.
     */
    @Query(
        "SELECT * FROM parenting_plan_entries WHERE authorUid = :authorUid AND syncedToFirestore = 0"
    )
    suspend fun getUnsynced(authorUid: String): List<ParentingPlanEntryEntity>

    @Query(
        "UPDATE parenting_plan_entries SET syncedToFirestore = 1 " +
            "WHERE familyId = :familyId AND authorUid = :authorUid"
    )
    suspend fun markSynced(familyId: String, authorUid: String)
}
