package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.ChildInfoEntity
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.domain.model.ChildInfo
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * Unit tests for [ChildInfoRepositoryImpl]'s Firestore write path.
 *
 * A round-1 review found that [SyncService][com.coparently.app.data.sync.SyncService] was fixed
 * to publish the co-parent in `sharedWith`, but `ChildInfoRepositoryImpl.upsertChildInfo` — the
 * path an ordinary add or edit actually goes through, via `ChildInfoViewModel` — still built its
 * own `sharedWith` from `listOfNotNull(createdByFirebaseUid, lastModifiedBy)` and never included
 * the co-parent at all. Because `FirestoreChildInfoDataSource.upsertChildInfo` is a full `.set()`,
 * every ordinary edit also silently overwrote any audience a prior backfill had granted. This
 * class pins that both writers now agree, through the single [ChildInfoAudience][
 * com.coparently.app.data.sync.ChildInfoAudience] policy, and that a failed write no longer marks
 * the row synced — which had been stranding it out of the retry path forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChildInfoRepositoryImplTest {

    private lateinit var childInfoDao: ChildInfoDao
    private lateinit var userDao: UserDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreChildInfoDataSource: FirestoreChildInfoDataSource
    private lateinit var repository: ChildInfoRepositoryImpl

    @Before
    fun setup() {
        childInfoDao = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        firebaseAuthService = mockk(relaxed = true)
        firestoreChildInfoDataSource = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns ALICE
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser

        repository = ChildInfoRepositoryImpl(
            childInfoDao,
            userDao,
            firebaseAuthService,
            firestoreChildInfoDataSource
        )
    }

    @Test
    fun `upsertChildInfo publishes to the current co-parent and marks the row synced`() = runTest {
        coEvery { userDao.getUserById(ALICE) } returns userEntity(partnerId = BOB)
        coEvery { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) } returns
            Result.success(Unit)

        repository.upsertChildInfo(childInfo(createdByFirebaseUid = ALICE))

        val uploaded = slot<Map<String, Any?>>()
        coVerify { firestoreChildInfoDataSource.upsertChildInfo(CHILD_ID, capture(uploaded)) }
        assertEquals(
            listOf(ALICE, BOB),
            uploaded.captured["sharedWith"],
            "an ordinary add/edit must publish to the co-parent, not just creator+modifier"
        )

        val syncedEntity = slot<ChildInfoEntity>()
        coVerify { childInfoDao.updateChildInfo(capture(syncedEntity)) }
        assertEquals(true, syncedEntity.captured.syncedToFirestore)
    }

    @Test
    fun `upsertChildInfo does not mark the row synced when the Firestore write fails`() = runTest {
        coEvery { userDao.getUserById(ALICE) } returns userEntity(partnerId = BOB)
        coEvery { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) } returns
            Result.failure(IOException("offline"))

        repository.upsertChildInfo(childInfo(createdByFirebaseUid = ALICE))

        // The previous behaviour marked the row synced unconditionally, which stranded a
        // failed write out of `getUnsyncedChildInfo()`'s retry path forever.
        coVerify(exactly = 0) { childInfoDao.updateChildInfo(any()) }
    }

    @Test
    fun `an unpaired parent publishes only to themselves`() = runTest {
        coEvery { userDao.getUserById(ALICE) } returns userEntity(partnerId = null)
        coEvery { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) } returns
            Result.success(Unit)

        repository.upsertChildInfo(childInfo(createdByFirebaseUid = ALICE))

        val uploaded = slot<Map<String, Any?>>()
        coVerify { firestoreChildInfoDataSource.upsertChildInfo(CHILD_ID, capture(uploaded)) }
        assertEquals(listOf(ALICE), uploaded.captured["sharedWith"])
    }

    private fun childInfo(createdByFirebaseUid: String?) = ChildInfo(
        id = CHILD_ID,
        childName = "Ema",
        dateOfBirth = null,
        createdAt = NOW,
        updatedAt = NOW,
        createdByFirebaseUid = createdByFirebaseUid,
        lastModifiedBy = ALICE,
        syncedToFirestore = false
    )

    private fun userEntity(partnerId: String?) = UserEntity(
        id = ALICE,
        email = "alice@example.test",
        name = "Alice",
        role = "mom",
        colorCode = "#FF4081",
        partnerId = partnerId
    )

    private companion object {
        const val ALICE = "alice-uid"
        const val BOB = "bob-uid"
        const val CHILD_ID = "child-1"
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)
    }
}
