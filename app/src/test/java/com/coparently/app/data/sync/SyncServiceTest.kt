package com.coparently.app.data.sync

import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.ChildInfoEntity
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals

/**
 * Unit tests for [SyncService]'s upload paths.
 *
 * Two properties are pinned here, both of which the pre-fix code got wrong:
 *
 * 1. The event upload audience must be the *entitled* set, not the stored list widened.
 *    `syncEvents` uploads unsynced events **before** it downloads, so under the widen-only
 *    rule every event still sitting `syncedToFirestore = false` when `unpairCoParent` ran
 *    re-granted the ex-partner access on the very next sync, permanently undoing the
 *    server-side revocation sweep.
 * 2. The `child_info` `ConflictResolution.UseLocal` branch must issue a partial
 *    `updateChildInfo`, never a full `upsertChildInfo`. `ChildInfoEntity` carries no
 *    `sharedWith`, so the `.set()` shape stripped the field and left the document
 *    unreadable and un-updatable for both parents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncServiceTest {

    private val gson = Gson()
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val now: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)

    private lateinit var eventDao: EventDao
    private lateinit var childInfoDao: ChildInfoDao
    private lateinit var userDao: UserDao
    private lateinit var firestoreEventDataSource: FirestoreEventDataSource
    private lateinit var firestoreChildInfoDataSource: FirestoreChildInfoDataSource
    private lateinit var firestoreUserDataSource: FirestoreUserDataSource
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var fcmService: FcmService
    private lateinit var syncService: SyncService

    @Before
    fun setup() {
        eventDao = mockk(relaxed = true)
        childInfoDao = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        firestoreEventDataSource = mockk(relaxed = true)
        firestoreChildInfoDataSource = mockk(relaxed = true)
        firestoreUserDataSource = mockk(relaxed = true)
        firebaseAuthService = mockk(relaxed = true)
        fcmService = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns ALICE
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser

        coEvery { fcmService.getCurrentToken() } returns null
        coEvery { firestoreUserDataSource.getUserById(any()) } returns null
        coEvery { eventDao.getUnsyncedEvents() } returns emptyList()
        coEvery { childInfoDao.getUnsyncedChildInfo() } returns emptyList()
        every { firestoreEventDataSource.observeEventsSharedWith(any()) } returns
            flowOf(emptyList())
        every { firestoreChildInfoDataSource.getChildInfoForParent(any()) } returns
            flowOf(emptyList())

        syncService = SyncService(
            eventDao,
            childInfoDao,
            userDao,
            firestoreEventDataSource,
            firestoreChildInfoDataSource,
            firestoreUserDataSource,
            firebaseAuthService,
            fcmService,
            // The real resolver, so the branch under test is the one production picks.
            ConflictResolver()
        )
    }

    @Test
    fun `uploading an unsynced event drops an ex-partner the unpair sweep removed`() = runTest {
        // Alice unpaired: the server sweep narrowed the remote documents, but this event
        // never reached Firestore, so its Room copy still lists Bob. Uploading it must not
        // hand Bob back the access the sweep just revoked.
        pairWith(partnerId = null)
        coEvery { eventDao.getUnsyncedEvents() } returns listOf(
            eventEntity(createdByFirebaseUid = ALICE, sharedWith = listOf(ALICE, BOB))
        )
        val uploaded = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(uploaded)) } returns
            Result.success(Unit)

        syncService.performFullSync()

        assertEquals(listOf(ALICE), uploaded.captured["sharedWith"])
    }

    @Test
    fun `uploading an unsynced event still shares with the current co-parent`() = runTest {
        pairWith(partnerId = BOB)
        coEvery { eventDao.getUnsyncedEvents() } returns listOf(
            eventEntity(createdByFirebaseUid = ALICE, sharedWith = emptyList())
        )
        val uploaded = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(uploaded)) } returns
            Result.success(Unit)

        syncService.performFullSync()

        assertEquals(listOf(ALICE, BOB), uploaded.captured["sharedWith"])
    }

    @Test
    fun `uploading an unsynced event keeps the creator when the co-parent uploads`() = runTest {
        // Bob's device uploads an edit to an event Alice created. Dropping the creator
        // would hide the event from the parent it belongs to, because `sharedWith` is what
        // the down-sync query is keyed on.
        pairWith(partnerId = null)
        coEvery { eventDao.getUnsyncedEvents() } returns listOf(
            eventEntity(createdByFirebaseUid = BOB, sharedWith = listOf(BOB, ALICE))
        )
        val uploaded = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(uploaded)) } returns
            Result.success(Unit)

        syncService.performFullSync()

        assertEquals(listOf(BOB, ALICE), uploaded.captured["sharedWith"])
    }

    @Test
    fun `uploading an unsynced event replaces a former co-parent with the new one`() = runTest {
        // Alice unpaired from Bob and re-paired with Carol. The audience must lose Bob and
        // gain Carol — intersecting must not cost the new co-parent their visibility.
        pairWith(partnerId = CAROL)
        coEvery { eventDao.getUnsyncedEvents() } returns listOf(
            eventEntity(createdByFirebaseUid = ALICE, sharedWith = listOf(ALICE, BOB))
        )
        val uploaded = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(uploaded)) } returns
            Result.success(Unit)

        syncService.performFullSync()

        assertEquals(listOf(ALICE, CAROL), uploaded.captured["sharedWith"])
    }

    @Test
    fun `a private unsynced event is never uploaded`() = runTest {
        pairWith(partnerId = BOB)
        coEvery { eventDao.getUnsyncedEvents() } returns listOf(
            eventEntity(createdByFirebaseUid = ALICE, sharedWith = listOf(ALICE))
                .copy(isPrivate = true)
        )

        syncService.performFullSync()

        coVerify(exactly = 0) { firestoreEventDataSource.insertEvent(any(), any()) }
    }

    @Test
    fun `the child info UseLocal branch updates instead of overwriting the document`() = runTest {
        // A full `.set()` here strips `sharedWith`, which `ChildInfoEntity` does not carry:
        // the document then fails `request.auth.uid in resource.data.sharedWith` for
        // everybody, for good. The partial update leaves the field alone.
        pairWith(partnerId = BOB)
        val local = childInfoEntity(updatedAt = now.plusHours(1), synced = false)
        every { firestoreChildInfoDataSource.getChildInfoForParent(ALICE) } returns
            flowOf(listOf(remoteChildInfoMap(updatedAt = now)))
        coEvery { childInfoDao.getChildInfoById(CHILD_ID) } returns local

        syncService.performFullSync()

        coVerify(exactly = 1) { firestoreChildInfoDataSource.updateChildInfo(CHILD_ID, any()) }
        coVerify(exactly = 0) { firestoreChildInfoDataSource.upsertChildInfo(CHILD_ID, any()) }
    }

    @Test
    fun `the child info UseRemote branch writes no remote document at all`() = runTest {
        // Guards the assertion above against passing for the wrong reason: it must be the
        // UseLocal branch that is exercised, not a silent fall-through.
        pairWith(partnerId = BOB)
        val local = childInfoEntity(updatedAt = now.minusHours(1), synced = false)
        every { firestoreChildInfoDataSource.getChildInfoForParent(ALICE) } returns
            flowOf(listOf(remoteChildInfoMap(updatedAt = now)))
        coEvery { childInfoDao.getChildInfoById(CHILD_ID) } returns local

        syncService.performFullSync()

        coVerify(exactly = 0) { firestoreChildInfoDataSource.updateChildInfo(any(), any()) }
        coVerify(exactly = 0) { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) }
    }

    /** Gives Alice's Room row the supplied co-parent (or none). */
    private fun pairWith(partnerId: String?) {
        coEvery { userDao.getUserById(ALICE) } returns UserEntity(
            id = ALICE,
            email = "alice@example.test",
            name = "Alice",
            role = "mom",
            colorCode = "#FF4081",
            partnerId = partnerId
        )
    }

    private fun eventEntity(createdByFirebaseUid: String, sharedWith: List<String>) = EventEntity(
        id = "event-1",
        title = "Swimming lesson",
        startDateTime = now,
        endDateTime = now.plusHours(1),
        eventType = "activity",
        parentOwner = "mom",
        createdAt = now,
        updatedAt = now,
        createdByFirebaseUid = createdByFirebaseUid,
        sharedWithJson = gson.toJson(sharedWith)
    )

    private fun childInfoEntity(updatedAt: LocalDateTime, synced: Boolean) = ChildInfoEntity(
        id = CHILD_ID,
        childName = "Ema",
        dateOfBirth = null,
        medicationsJson = "[]",
        activitiesJson = "[]",
        allergiesJson = "[]",
        medicalNotes = null,
        emergencyContactsJson = "[]",
        schoolInfoJson = null,
        createdAt = now,
        updatedAt = updatedAt,
        createdByFirebaseUid = ALICE,
        lastModifiedBy = ALICE,
        syncedToFirestore = synced
    )

    private fun remoteChildInfoMap(updatedAt: LocalDateTime): Map<String, Any?> = mapOf(
        "id" to CHILD_ID,
        "childName" to "Ema",
        "dateOfBirth" to null,
        "medications" to emptyList<Any>(),
        "activities" to emptyList<Any>(),
        "allergies" to emptyList<String>(),
        "medicalNotes" to null,
        "emergencyContacts" to emptyList<Any>(),
        "schoolInfo" to null,
        "createdAt" to now.format(formatter),
        "updatedAt" to updatedAt.format(formatter),
        "createdByFirebaseUid" to ALICE,
        "lastModifiedBy" to ALICE,
        "sharedWith" to listOf(ALICE, BOB)
    )

    private companion object {
        const val ALICE = "alice-uid"
        const val BOB = "bob-uid"
        const val CAROL = "carol-uid"
        const val CHILD_ID = "child-1"
    }
}
