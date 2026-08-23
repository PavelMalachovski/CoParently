package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.domain.activity.ActivityAnnouncer
import com.coparently.app.domain.events.EventAcceptance
import com.coparently.app.domain.model.Event
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * Unit tests for [EventRepositoryImpl], focused on the entity<->domain mappers.
 *
 * Guards the 2026-07 review §2.1 data-loss regression: the mappers must round-trip
 * [Event.sharedWith], [Event.permissions] and [Event.lastModifiedBy] through Room,
 * otherwise editing an event silently drops its sharing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventRepositoryImplTest {

    private val gson = Gson()

    private lateinit var eventDao: EventDao
    private lateinit var userDao: UserDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreEventDataSource: FirestoreEventDataSource
    private lateinit var activityAnnouncer: ActivityAnnouncer
    private lateinit var repository: EventRepositoryImpl

    private val now = LocalDateTime.of(2026, 7, 23, 10, 0)

    @Before
    fun setup() {
        eventDao = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        firestoreEventDataSource = mockk(relaxed = true)
        firebaseAuthService = mockk()
        // No signed-in user -> insert/update stay local, so the Firestore path doesn't
        // interfere with what we assert about the persisted entity.
        every { firebaseAuthService.getCurrentUser() } returns null
        activityAnnouncer = mockk(relaxed = true)
        repository = EventRepositoryImpl(
            eventDao,
            userDao,
            firebaseAuthService,
            firestoreEventDataSource,
            activityAnnouncer
        )
    }

    @Test
    fun `getEventById maps sharedWith, permissions and lastModifiedBy back to domain`() = runTest {
        val entity = baseEntity().copy(
            sharedWithJson = gson.toJson(listOf("uidA", "uidB")),
            permissions = "read_only",
            lastModifiedBy = "uidX"
        )
        coEvery { eventDao.getEventById("e1") } returns entity

        val event = repository.getEventById("e1")

        assertEquals(listOf("uidA", "uidB"), event?.sharedWith)
        assertEquals("read_only", event?.permissions)
        assertEquals("uidX", event?.lastModifiedBy)
    }

    @Test
    fun `insertEvent persists sharedWith, permissions and lastModifiedBy`() = runTest {
        val event = baseDomain().copy(
            sharedWith = listOf("uidA", "uidB"),
            permissions = "read_only",
            lastModifiedBy = "uidX"
        )
        val captured = slot<EventEntity>()
        coEvery { eventDao.insertEvent(capture(captured)) } returns Unit

        repository.insertEvent(event)

        coVerify { eventDao.insertEvent(any()) }
        val stored = captured.captured
        assertEquals(listOf("uidA", "uidB"), gson.fromJson(stored.sharedWithJson, Array<String>::class.java).toList())
        assertEquals("read_only", stored.permissions)
        assertEquals("uidX", stored.lastModifiedBy)
    }

    @Test
    fun `insertEvent shares the remote document with both parents`() = runTest {
        signIn(uid = "uidA", partnerId = "uidB")
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.insertEvent(baseDomain())

        assertEquals(listOf("uidA", "uidB"), captured.captured["sharedWith"])
    }

    @Test
    fun `insertEvent shares only with the creator when unpaired`() = runTest {
        signIn(uid = "uidA", partnerId = null)
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.insertEvent(baseDomain())

        assertEquals(listOf("uidA"), captured.captured["sharedWith"])
    }

    @Test
    fun `updateEvent by the co-parent keeps the creator in sharedWith`() = runTest {
        // uidB (the co-parent) edits an event uidA created: the audience must be widened,
        // never recomputed from the editor alone, or the creator loses sight of their own event.
        signIn(uid = "uidB", partnerId = "uidA")
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.updateEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.updateEvent(
            baseDomain().copy(createdByFirebaseUid = "uidA", sharedWith = listOf("uidA"), syncedToFirestore = true)
        )

        assertEquals(listOf("uidA", "uidB"), captured.captured["sharedWith"])
        assertEquals("uidA", captured.captured["createdByFirebaseUid"])
    }

    @Test
    fun `updateEvent drops an ex-partner the unpair sweep already revoked`() = runTest {
        // `unpairCoParent` narrowed the remote document, but the sweep never touches Room,
        // so the local copy still lists uidB. Under the old widen-only rule this edit
        // uploaded uidB straight back — the creator's own write is allowed by the events
        // update rule, and the ex-partner's array-contains down-sync then picked the event
        // back up, indefinitely. The audience must come from live pairing state instead.
        signIn(uid = "uidA", partnerId = null)
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.updateEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.updateEvent(
            baseDomain().copy(
                createdByFirebaseUid = "uidA",
                sharedWith = listOf("uidA", "uidB"),
                syncedToFirestore = true
            )
        )

        assertEquals(listOf("uidA"), captured.captured["sharedWith"])
    }

    @Test
    fun `updateEvent replaces a former co-parent with the current one`() = runTest {
        // uidA unpaired from uidB and re-paired with uidC. Intersecting the stored audience
        // must still let the *new* co-parent in, or old events stay invisible to them.
        signIn(uid = "uidA", partnerId = "uidC")
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.updateEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.updateEvent(
            baseDomain().copy(
                createdByFirebaseUid = "uidA",
                sharedWith = listOf("uidA", "uidB"),
                syncedToFirestore = true
            )
        )

        assertEquals(listOf("uidA", "uidC"), captured.captured["sharedWith"])
    }

    @Test
    fun `updateEvent converges the Room copy on the uploaded audience`() = runTest {
        // Otherwise the stale audience survives locally until the next down-sync, which is
        // exactly the window the server-side sweep cannot reach.
        signIn(uid = "uidA", partnerId = null)
        coEvery { firestoreEventDataSource.updateEvent(any(), any()) } returns Result.success(Unit)
        val stored = mutableListOf<EventEntity>()
        coEvery { eventDao.updateEvent(capture(stored)) } returns Unit

        repository.updateEvent(
            baseDomain().copy(
                createdByFirebaseUid = "uidA",
                sharedWith = listOf("uidA", "uidB"),
                syncedToFirestore = true
            )
        )

        val persisted = gson.fromJson(stored.last().sharedWithJson, Array<String>::class.java).toList()
        assertEquals(listOf("uidA"), persisted)
    }

    @Test
    fun `private events are never pushed to Firestore`() = runTest {
        signIn(uid = "uidA", partnerId = "uidB")

        repository.insertEvent(baseDomain().copy(isPrivate = true))

        coVerify(exactly = 0) { firestoreEventDataSource.insertEvent(any(), any()) }
    }

    @Test
    fun `a private event created while unpaired is still stamped with its creator's uid`() = runTest {
        // Private events are never synced (see the test above), so the Firestore-sync branch
        // that used to be the only place `createdByFirebaseUid` was set never runs for them.
        // Without a stamp at insert time this field stays null forever, and
        // ParentSlotMigrator's `WHERE createdByFirebaseUid = :myUid` scoping clause can never
        // find the row to re-stamp once pairing moves this device to a different slot.
        signIn(uid = "uidA", partnerId = null)
        val captured = slot<EventEntity>()
        coEvery { eventDao.insertEvent(capture(captured)) } returns Unit

        repository.insertEvent(baseDomain().copy(isPrivate = true))

        assertEquals("uidA", captured.captured.createdByFirebaseUid)
    }

    @Test
    fun `insertEvent stamps createdByFirebaseUid even when the Firestore write is skipped for other reasons`() =
        runTest {
            // Same hazard as the private-event case above, but for an event that is eligible
            // to sync and simply has not yet (offline, or a prior sync attempt failed): the
            // Firestore branch is what used to do the stamping, and it does not run here either.
            signIn(uid = "uidA", partnerId = null)
            val captured = slot<EventEntity>()
            coEvery { eventDao.insertEvent(capture(captured)) } returns Unit

            repository.insertEvent(baseDomain().copy(syncedToFirestore = true))

            assertEquals("uidA", captured.captured.createdByFirebaseUid)
        }

    @Test
    fun `insertEvent never overwrites an already-stamped creator uid`() = runTest {
        signIn(uid = "uidA", partnerId = null)
        val captured = slot<EventEntity>()
        coEvery { eventDao.insertEvent(capture(captured)) } returns Unit

        repository.insertEvent(baseDomain().copy(createdByFirebaseUid = "someone-else"))

        assertEquals("someone-else", captured.captured.createdByFirebaseUid)
    }

    @Test
    fun `getEventById tolerates blank sharedWith json`() = runTest {
        coEvery { eventDao.getEventById("e1") } returns baseEntity().copy(sharedWithJson = "")

        val event = repository.getEventById("e1")

        assertEquals(emptyList(), event?.sharedWith)
    }

    /** Puts [uid] in the auth service and gives their Room row [partnerId]. */
    private fun signIn(uid: String, partnerId: String?) {
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns uid
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById(uid) } returns UserEntity(
            id = uid,
            email = "$uid@example.com",
            name = uid,
            role = "mom",
            colorCode = "#FF4081",
            partnerId = partnerId
        )
    }

    @Test
    fun `a range query hides an event still waiting on the co-parent`() = runTest {
        every { eventDao.getSingleEventsByDateRange(any(), any()) } returns flowOf(
            listOf(
                baseEntity().copy(id = "ordinary"),
                baseEntity().copy(id = "pending", acceptance = "PENDING"),
                baseEntity().copy(id = "declined", acceptance = "DECLINED"),
                baseEntity().copy(id = "accepted", acceptance = "ACCEPTED")
            )
        )
        every { eventDao.getRecurringEventsStartedBefore(any()) } returns flowOf(emptyList())

        val events = repository.getEventsByDateRange(now.minusDays(1), now.plusDays(1)).first()

        // This is the one query the month grid, the week view and the day view all read from, so
        // one filter here is what keeps three views from disagreeing about the same event.
        assertEquals(listOf("ordinary", "accepted"), events.map { it.id })
    }

    @Test
    fun `a recurring series waiting on the co-parent produces no occurrences at all`() = runTest {
        // Filtered before expansion, not after: occurrences share their master's id and carry
        // nothing of their own, so there is no per-occurrence status to filter on later.
        every { eventDao.getSingleEventsByDateRange(any(), any()) } returns flowOf(emptyList())
        every { eventDao.getRecurringEventsStartedBefore(any()) } returns flowOf(
            listOf(
                baseEntity().copy(
                    id = "weekly",
                    isRecurring = true,
                    recurrencePattern = "weekly",
                    acceptance = "PENDING"
                )
            )
        )

        val events = repository.getEventsByDateRange(now.minusDays(1), now.plusDays(30)).first()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `a single day query applies the same rule as the range query`() = runTest {
        every { eventDao.getEventsByDate(any()) } returns flowOf(
            listOf(
                baseEntity().copy(id = "ordinary"),
                baseEntity().copy(id = "pending", acceptance = "PENDING")
            )
        )

        val events = repository.getEventsByDate(now).first()

        assertEquals(listOf("ordinary"), events.map { it.id })
    }

    @Test
    fun `the mappers round-trip acceptance through Room`() = runTest {
        val entity = baseEntity().copy(
            acceptance = "ACCEPTED",
            acceptedBy = "uid-dad",
            acceptedAt = now
        )
        coEvery { eventDao.getEventById("e1") } returns entity

        val event = repository.getEventById("e1")

        assertEquals(EventAcceptance.ACCEPTED, event?.acceptance)
        assertEquals("uid-dad", event?.acceptedBy)
        assertEquals(now, event?.acceptedAt)
    }

    @Test
    fun `an unrecognised status from a newer build reads as not required, rather than throwing`() =
        runTest {
            // The safe direction: the worst case is an event that shows when a newer build would
            // have hidden it, against a crash on the path that draws the grid.
            coEvery { eventDao.getEventById("e1") } returns baseEntity().copy(acceptance = "SOMEDAY")

            assertEquals(EventAcceptance.NOT_REQUIRED, repository.getEventById("e1")?.acceptance)
        }

    @Test
    fun `the mappers round-trip the important flag through Room`() = runTest {
        coEvery { eventDao.getEventById("e1") } returns baseEntity().copy(isImportant = true)

        assertEquals(true, repository.getEventById("e1")?.isImportant)
    }

    @Test
    fun `the important flag reaches the Firestore document`() = runTest {
        // The flag lives at four map sites and missing one is silent — a field written by
        // `toFirestoreMap` but absent from `SyncService`'s own maps is deleted on the next
        // sync, which is exactly the defect package B1 shipped.
        signIn(uid = "uidA", partnerId = "uidB")
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.insertEvent(baseDomain().copy(isImportant = true))

        assertEquals(true, captured.captured["isImportant"])
    }

    @Test
    fun `an ordinary event is written unmarked, not omitted`() = runTest {
        // Written as `false` rather than left out: a reader that treats an absent key as false
        // and one that treats it as unknown would disagree, and only one of them is this app.
        signIn(uid = "uidA", partnerId = "uidB")
        val captured = slot<Map<String, Any?>>()
        coEvery { firestoreEventDataSource.insertEvent(any(), capture(captured)) } returns Result.success(Unit)

        repository.insertEvent(baseDomain())

        assertEquals(false, captured.captured["isImportant"])
    }

    private fun baseEntity() = EventEntity(
        id = "e1",
        title = "Soccer",
        startDateTime = now,
        endDateTime = now.plusHours(1),
        eventType = "sports",
        parentOwner = "mom",
        createdAt = now,
        updatedAt = now
    )

    private fun baseDomain() = Event(
        id = "e1",
        title = "Soccer",
        startDateTime = now,
        endDateTime = now.plusHours(1),
        eventType = "sports",
        parentOwner = "mom",
        createdAt = now,
        updatedAt = now
    )
}
