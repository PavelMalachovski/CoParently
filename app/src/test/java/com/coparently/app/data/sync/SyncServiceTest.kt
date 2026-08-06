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
import com.coparently.app.data.repository.ParentSlotMigrator
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals

/**
 * Unit tests for [SyncService]'s upload paths, and for [SyncService.syncUserData]'s `role`
 * refresh and slot-change reaction.
 *
 * Three properties are pinned here, the first two of which the pre-fix code got wrong:
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
 * 3. A failure reacting to a slot change must not fail the rest of the sync pass. This is the
 *    only test coverage there is for that containment — a round-1 review found the earlier
 *    version of this file exercised none of `syncUserData`'s role/slot-change branch at all,
 *    since every pre-existing test stubs the remote user to `null`.
 * 4. A parent-slot re-stamp performed in step 1 must still be there when step 2 has finished.
 *    Steps 1 and 2 were each covered on their own and the seam between them was not: the
 *    re-stamp wrote Room directly, the upload half only looks at `getUnsyncedEvents()`, and the
 *    download half REPLACEs whatever it receives — so the re-stamp was reverted seconds later,
 *    in the same pass, with no error and no log, and the slot marker had already advanced so
 *    nothing ever retried it.
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
    private lateinit var parentSlotMigrator: ParentSlotMigrator
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
        parentSlotMigrator = mockk(relaxed = true)

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
            ConflictResolver(),
            parentSlotMigrator
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

    @Test
    fun `syncUserData persists the role a remote document carries`() = runTest {
        pairWith(partnerId = BOB)
        coEvery { firestoreUserDataSource.getUserById(ALICE) } returns mapOf("role" to "dad")

        syncService.performFullSync()

        val row = slot<UserEntity>()
        coVerify { userDao.updateUser(capture(row)) }
        assertEquals("dad", row.captured.role)
    }

    @Test
    fun `syncUserData hands the migrator the incoming role`() = runTest {
        // Alice's local row says "mom"; the remote document already says "dad". The migrator,
        // not this test, decides whether that is a real change — this only pins that the
        // value it is handed is the one the document actually carries.
        pairWith(partnerId = BOB)
        coEvery { firestoreUserDataSource.getUserById(ALICE) } returns mapOf("role" to "dad")

        syncService.performFullSync()

        coVerify(exactly = 1) {
            parentSlotMigrator.reslotIfSlotChanged(myUid = ALICE, newRole = "dad")
        }
    }

    @Test
    fun `a blank remote role does not blank out the role Room already has`() = runTest {
        // `role` is non-nullable, unlike partnerId/fcmToken: there is no "unknown slot" this
        // entity can fall back to on its own, so a document carrying `role: ""` — which
        // should never happen, but `as? String` would accept it — must not land in Room, or
        // it would be stamped onto new records and then permanently block a real re-stamp of
        // them (the migrator's own guard treats a blank incoming role as absent).
        pairWith(partnerId = BOB)
        coEvery { firestoreUserDataSource.getUserById(ALICE) } returns mapOf("role" to "")

        syncService.performFullSync()

        val row = slot<UserEntity>()
        coVerify { userDao.updateUser(capture(row)) }
        assertEquals("mom", row.captured.role)
    }

    @Test
    fun `a re-stamped event survives the download half of the same sync pass`() = runTest {
        // The seam neither scoped review could see. `syncUserData` re-stamps this user's rows in
        // step 1 and `syncEvents` runs in step 2 of the *same* pass: its upload half only looks at
        // `getUnsyncedEvents()`, and its download half REPLACEs every row it receives. Unless the
        // re-stamp also marks the rows unsynced, the upload skips them, the document keeps the
        // pre-pairing slot, and the download writes that slot straight back over the re-stamp —
        // permanently, because `reslot` has already advanced the slot marker and
        // `reslotIfSlotChanged` never retries once `marker == to`.
        pairWith(partnerId = BOB)
        val rows = mutableMapOf(
            EVENT_ID to eventEntity(createdByFirebaseUid = ALICE, sharedWith = listOf(ALICE))
                .copy(syncedToFirestore = true)
        )
        // The pair's document as it stands before this pass: the old slot, an audience of one.
        var document = eventDocument(rows.getValue(EVENT_ID), sharedWith = listOf(ALICE))
        backEventDaoWith(rows)
        coEvery { firestoreEventDataSource.insertEvent(any(), any()) } answers {
            document = secondArg()
            Result.success(Unit)
        }
        // A live listener re-delivers the document as it stands when the download half runs.
        every { firestoreEventDataSource.observeEventsSharedWith(ALICE) } returns
            flow { emit(listOf(document)) }
        coEvery { firestoreUserDataSource.getUserById(ALICE) } returns mapOf("role" to "dad")
        // Read here rather than inside the stub: `syncUserData` swallows everything the migrator
        // throws, so a failure to read the statement would vanish into a confusing assertion.
        val clearsSyncFlag = declaredSql("reslotOwner")
            .filterNot { it.isWhitespace() }
            .contains("syncedToFirestore=0")
        coEvery { parentSlotMigrator.reslotIfSlotChanged(ALICE, "dad") } answers {
            applyReslot(rows, from = "mom", to = "dad", myUid = ALICE, clearsSyncFlag)
        }

        syncService.performFullSync()

        assertEquals("dad", rows.getValue(EVENT_ID).parentOwner)
        // And the other half of the spec's promise: the document the co-parent reads now carries
        // the new slot *and* lists them, so it reaches them at all.
        assertEquals("dad", document["parentOwner"])
        assertEquals(listOf(ALICE, BOB), document["sharedWith"])
    }

    @Test
    fun `a failure reacting to a slot change does not fail the rest of the sync pass`() = runTest {
        // The containment a round-1 review specifically asked to see covered: `syncEvents`
        // and `syncChildInfo` run after `syncUserData` in the same `performFullSync` pass, so
        // an uncaught exception here would have silently dropped both for this pass too.
        pairWith(partnerId = BOB)
        coEvery { firestoreUserDataSource.getUserById(ALICE) } returns mapOf("role" to "dad")
        coEvery {
            parentSlotMigrator.reslotIfSlotChanged(myUid = ALICE, newRole = "dad")
        } throws IllegalStateException("boom")
        coEvery { eventDao.getUnsyncedEvents() } returns listOf(
            eventEntity(createdByFirebaseUid = ALICE, sharedWith = listOf(ALICE, BOB))
        )
        coEvery { firestoreEventDataSource.insertEvent(any(), any()) } returns Result.success(Unit)

        val result = syncService.performFullSync()

        assert(result.isSuccess) { "a re-stamp failure must not fail the whole sync pass" }
        coVerify(exactly = 1) { firestoreEventDataSource.insertEvent(any(), any()) }
    }

    /**
     * Backs the mocked [EventDao] with [rows], so a write in one half of a sync pass is visible
     * to the half that follows it. The default `relaxed` mock forgets everything, which is
     * exactly why the two halves could disagree unnoticed.
     */
    private fun backEventDaoWith(rows: MutableMap<String, EventEntity>) {
        coEvery { eventDao.getUnsyncedEvents() } answers {
            rows.values.filterNot { it.syncedToFirestore }
        }
        coEvery { eventDao.getEventById(any()) } answers { rows[firstArg()] }
        coEvery { eventDao.insertEvent(any()) } answers {
            val entity = firstArg<EventEntity>()
            rows[entity.id] = entity
        }
        coEvery { eventDao.markAsSynced(any()) } answers {
            val id = firstArg<String>()
            rows[id]?.let { rows[id] = it.copy(syncedToFirestore = true) }
        }
    }

    /**
     * Applies [EventDao]'s two re-stamp statements to [rows] the way SQLite would.
     *
     * The one effect this fake does not restate is [clearsSyncFlag], which the caller reads off
     * the declared statement via [declaredSql]. There is no Room/Robolectric harness on this
     * source set, so the SQL cannot be executed here — deriving the behaviour that matters from
     * the production statement rather than from a copy of it is what stops this test from
     * passing while the statement says otherwise.
     */
    private fun applyReslot(
        rows: MutableMap<String, EventEntity>,
        from: String,
        to: String,
        myUid: String,
        clearsSyncFlag: Boolean
    ) {
        rows.keys.toList()
            .map { id -> id to rows.getValue(id) }
            .filter { (_, row) -> row.createdByFirebaseUid == myUid }
            .forEach { (id, row) ->
                val restamped = row
                    .let { if (it.parentOwner == from) it.copy(parentOwner = to) else it }
                    .let { if (it.pickupConfirmedBy == from) it.copy(pickupConfirmedBy = to) else it }
                if (restamped == row) return@forEach
                rows[id] =
                    if (clearsSyncFlag) restamped.copy(syncedToFirestore = false) else restamped
            }
    }

    /**
     * The SQL [EventDao] declares for [method], read out of the source file.
     *
     * Not reflection: Room's `@Query` is `BINARY`-retention and is simply not there at runtime.
     * Not the generated `EventDao_Impl` either — its statement is a string constant inside a
     * method body. The source is the only place the statement can be read from on a plain-JVM
     * source set, and reading it is what gives [applyDeclaredReslot] its teeth.
     */
    private fun declaredSql(method: String): String {
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, DAO_SOURCE_PATH) }
            .first { it.isFile }
            .readText()
        val declaredAt = source.indexOf("suspend fun $method(")
        check(declaredAt > 0) { "EventDao.$method is not declared where this test expects it" }
        return source.substring(0, declaredAt).substringAfterLast("@Query(")
    }

    /** The Firestore document `SyncService` would write for [entity]. */
    private fun eventDocument(entity: EventEntity, sharedWith: List<String>): Map<String, Any?> =
        mapOf(
            "id" to entity.id,
            "title" to entity.title,
            "description" to entity.description,
            "startDateTime" to entity.startDateTime.format(formatter),
            "endDateTime" to entity.endDateTime?.format(formatter),
            "eventType" to entity.eventType,
            "parentOwner" to entity.parentOwner,
            "isRecurring" to entity.isRecurring,
            "recurrencePattern" to entity.recurrencePattern,
            "recurrenceEndDate" to entity.recurrenceEndDate?.toString(),
            "pickupConfirmedBy" to entity.pickupConfirmedBy,
            "pickupConfirmedAt" to entity.pickupConfirmedAt?.format(formatter),
            "createdAt" to entity.createdAt.format(formatter),
            "updatedAt" to entity.updatedAt.format(formatter),
            "createdByFirebaseUid" to entity.createdByFirebaseUid,
            "sharedWith" to sharedWith,
            "lastModifiedBy" to entity.lastModifiedBy,
            "permissions" to entity.permissions,
            "imageUrl" to entity.imageUrl
        )

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
        id = EVENT_ID,
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
        const val EVENT_ID = "event-1"

        /** Where [SyncServiceTest.declaredSql] looks, relative to the repository root. */
        const val DAO_SOURCE_PATH =
            "app/src/main/java/com/coparently/app/data/local/dao/EventDao.kt"
    }
}
