package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.CustodyModelDao
import com.coparently.app.data.local.entity.CustodyModelEntity
import com.coparently.app.data.remote.firebase.FirestoreCustodyDataSource
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.UserRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [CustodyModelRepository]'s Firestore path, in both directions.
 *
 * The repository talked only to Room, so after pairing the second phone had no custody pattern
 * at all. What is asserted here is the shape of the fix rather than the fact that a write
 * happens: Room is written before Firestore and survives a refused remote write; an unpaired
 * user touches Firestore not at all; the mirror never discards a local model newer than the
 * document it just received; and the stream *keeps delivering after a failure* at every stage —
 * the defect `MessageRepositoryImpl` still ships, where a terminal `catch` completes the mirror
 * flow and leaves the feature running on Room alone for the rest of the process.
 *
 * The repository is built with a sharing scope on [dispatcher] and every test runs on the same
 * scheduler, so what is collected is [CustodyModelRepository.observeShared] itself — the real
 * shared flow, `shareIn` and all — with backoff delays elapsing on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustodyModelRepositoryTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private lateinit var custodyModelDao: CustodyModelDao
    private lateinit var userRepository: UserRepository
    private lateinit var firestoreCustodyDataSource: FirestoreCustodyDataSource
    private lateinit var repository: CustodyModelRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        custodyModelDao = mockk(relaxed = true)
        userRepository = mockk()
        firestoreCustodyDataSource = mockk()
        repository = CustodyModelRepository(
            custodyModelDao, userRepository, firestoreCustodyDataSource, CoroutineScope(dispatcher)
        )

        pairedWith(PARTNER_UID)
        coEvery { custodyModelDao.getModelById(any()) } returns null
        coEvery { custodyModelDao.getActiveModelSync() } returns null
        coEvery { firestoreCustodyDataSource.getCustody(any()) } returns null
        coEvery { firestoreCustodyDataSource.setCustody(any(), any(), any()) } returns Unit
        every { firestoreCustodyDataSource.observeCustody(any()) } returns flowOf(null)
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // ---- writing ----------------------------------------------------------

    @Test
    fun `saving writes Room before Firestore`() = runTest(dispatcher) {
        val custody = slot<SharedCustody>()
        coEvery {
            firestoreCustodyDataSource.setCustody(any(), any(), capture(custody))
        } returns Unit

        repository.saveAndActivate(localModel())

        // Room is the source of truth: the remote write is what happens *after* the local one
        // has already succeeded, never instead of it.
        coVerifyOrder {
            custodyModelDao.deactivateAllModels()
            custodyModelDao.insertModel(any())
            firestoreCustodyDataSource.setCustody(DOCUMENT_ID, PARTICIPANTS, any())
        }
        assertEquals(MY_UID, custody.captured.lastModifiedBy)
        assertEquals(LOCAL_MODEL_ID, custody.captured.model.id)
    }

    @Test
    fun `participants are written sorted even when the partner uid sorts first`() =
        runTest(dispatcher) {
            // firestore.rules requires the *stored* array to satisfy participants[0] <
            // participants[1] on create, and compares it order-sensitively on every update, so
            // an unsorted write would deny every later write to this pair's document forever.
            // The fixture's uids are deliberately in the wrong order for this to bite.
            pairedWith(EARLIER_PARTNER_UID)
            val participants = slot<List<String>>()
            coEvery {
                firestoreCustodyDataSource.setCustody(any(), capture(participants), any())
            } returns Unit

            repository.saveAndActivate(localModel())

            assertEquals(listOf(EARLIER_PARTNER_UID, MY_UID), participants.captured)
            // And the id is the canonical join of that same sorted pair — the rule binds the two
            // together, so a document whose id disagrees with its participants cannot be created.
            coVerify(exactly = 1) {
                firestoreCustodyDataSource.setCustody("uid0__uidA", any(), any())
            }
        }

    @Test
    fun `a Firestore failure on save leaves the local model in place`() = runTest(dispatcher) {
        coEvery {
            firestoreCustodyDataSource.setCustody(any(), any(), any())
        } throws permissionDenied()
        val entity = slot<CustodyModelEntity>()

        // Must not throw: three ViewModels call this from viewModelScope.launch, where an
        // uncaught PERMISSION_DENIED kills the process rather than failing the sync.
        repository.saveAndActivate(localModel())

        coVerify(exactly = 1) { custodyModelDao.insertModel(capture(entity)) }
        assertEquals(LOCAL_MODEL_ID, entity.captured.id)
        assertTrue(entity.captured.isActive)
    }

    @Test
    fun `an unpaired user saves to Room and writes no document`() = runTest(dispatcher) {
        pairedWith(partnerUid = null)
        val entity = slot<CustodyModelEntity>()

        repository.saveAndActivate(localModel())

        coVerify(exactly = 1) { custodyModelDao.insertModel(capture(entity)) }
        assertEquals(LOCAL_MODEL_ID, entity.captured.id)
        coVerify(exactly = 0) { firestoreCustodyDataSource.setCustody(any(), any(), any()) }
    }

    @Test
    fun `an update preserves the createdAt already on the document`() = runTest(dispatcher) {
        coEvery { firestoreCustodyDataSource.getCustody(DOCUMENT_ID) } returns remoteCustody()
        val custody = slot<SharedCustody>()
        coEvery {
            firestoreCustodyDataSource.setCustody(any(), any(), capture(custody))
        } returns Unit

        repository.saveAndActivate(localModel())

        // Editing the pattern must not re-date the pair's arrangement.
        assertEquals(REMOTE_CREATED_AT, custody.captured.createdAt)
    }

    // ---- a swallowed write must not become a silent revert -----------------

    @Test
    fun `a swallowed write is not undone by the mirror replaying the older document`() =
        runTest(dispatcher) {
            val saved = slot<CustodyModelEntity>()
            coEvery { custodyModelDao.insertModel(capture(saved)) } returns Unit
            coEvery {
                firestoreCustodyDataSource.setCustody(any(), any(), any())
            } throws permissionDenied()

            repository.saveAndActivate(localModel())

            // Room now holds the user's choice — and only Room does, because the push was
            // swallowed. The document still holds the pattern that was mirrored before it.
            coEvery { custodyModelDao.getActiveModelSync() } returns saved.captured
            every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
                flowOf(remoteCustody(lastModifiedAt = LONG_AGO))

            repository.observeShared().first()

            // Exactly the one insert the save itself performed. `saveAndActivate` deactivated
            // every model, so the previously mirrored row is inactive and the equality guard
            // does not fire on the replay — without the staleness check the mirror would
            // reactivate the stale pattern and the user's setup would silently revert.
            coVerify(exactly = 1) { custodyModelDao.insertModel(any()) }
            coVerify(exactly = 1) { custodyModelDao.deactivateAllModels() }
            assertEquals(LOCAL_MODEL_ID, saved.captured.id)
        }

    @Test
    fun `a local model newer than the document is sent again rather than discarded`() =
        runTest(dispatcher) {
            coEvery { custodyModelDao.getActiveModelSync() } returns
                localEntity(lastModifiedAt = RECENTLY)
            every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
                flowOf(remoteCustody(lastModifiedAt = LONG_AGO))
            val custody = slot<SharedCustody>()
            coEvery {
                firestoreCustodyDataSource.setCustody(any(), any(), capture(custody))
            } returns Unit

            repository.observeShared().first()

            // Refusing to mirror alone would leave the pair permanently disagreeing, with the
            // co-parent reading this device's own lost write. The listener has just proved it
            // is alive, so the write that failed gets one more attempt.
            coVerify(exactly = 1) {
                firestoreCustodyDataSource.setCustody(DOCUMENT_ID, any(), any())
            }
            assertEquals(LOCAL_MODEL_ID, custody.captured.model.id)
            assertEquals(RECENTLY, custody.captured.lastModifiedAt)
        }

    @Test
    fun `a document newer than the local model still wins`() = runTest(dispatcher) {
        coEvery { custodyModelDao.getActiveModelSync() } returns
            localEntity(lastModifiedAt = LONG_AGO)
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
            flowOf(remoteCustody(lastModifiedAt = RECENTLY))
        val entity = slot<CustodyModelEntity>()

        repository.observeShared().first()

        // The guard is "do not discard something newer", not "never mirror again": the
        // co-parent changing the schedule must still reach this device.
        coVerify(exactly = 1) { custodyModelDao.insertModel(capture(entity)) }
        assertEquals(REMOTE_MODEL_ID, entity.captured.id)
        coVerify(exactly = 0) { firestoreCustodyDataSource.setCustody(any(), any(), any()) }
    }

    // ---- reading ----------------------------------------------------------

    @Test
    fun `an unpaired user has no shared document`() = runTest(dispatcher) {
        pairedWith(partnerUid = null)

        assertNull(repository.getShared())
        assertNull(repository.observeShared().first())

        coVerify(exactly = 0) { firestoreCustodyDataSource.getCustody(any()) }
        coVerify(exactly = 0) { firestoreCustodyDataSource.observeCustody(any()) }
    }

    @Test
    fun `the one-shot read reaches the document derived from the two uids`() =
        runTest(dispatcher) {
            coEvery { firestoreCustodyDataSource.getCustody(DOCUMENT_ID) } returns remoteCustody()

            val shared = repository.getShared()

            assertEquals(REMOTE_MODEL_ID, shared?.model?.id)
            assertEquals(PARTNER_UID, shared?.lastModifiedBy)
        }

    @Test
    fun `a failed one-shot read degrades to null rather than propagating`() = runTest(dispatcher) {
        coEvery { firestoreCustodyDataSource.getCustody(DOCUMENT_ID) } throws permissionDenied()

        assertNull(repository.getShared())
    }

    @Test
    fun `the observer keeps delivering after a failed listener`() = runTest(dispatcher) {
        val collections = AtomicInteger()
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns flow {
            if (collections.getAndIncrement() == 0) throw permissionDenied()
            emit(remoteCustody())
        }
        val startedAt = scheduler.currentTime

        // Fails — the exception escapes and the test errors out — without `retryWhen` on the
        // upstream. A terminal `catch` instead would make it fail differently and worse: the
        // flow would complete having emitted nothing, silently, forever.
        val delivered = repository.observeShared().first { it != null }

        assertEquals(REMOTE_MODEL_ID, delivered?.model?.id)
        assertEquals(2, collections.get())
        // Not just "it retried" but "it waited first": under `runTest` the virtual clock skips
        // any delay, so a backoff of zero would satisfy every other assertion here.
        assertEquals(FIRST_BACKOFF_MS, scheduler.currentTime - startedAt)
    }

    @Test
    fun `a failure in the Room mirror does not terminate the stream`() = runTest(dispatcher) {
        val writes = AtomicInteger()
        coEvery { custodyModelDao.insertModel(any()) } answers {
            if (writes.getAndIncrement() == 0) error("Room write failed") else Unit
        }
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
            flowOf(remoteCustody())

        // The mirror runs on the sharing coroutine, outside every guard the write path has. Its
        // failure used to reach that scope's root and kill the process, while subscribers saw
        // nothing at all — a shared flow never delivers an upstream failure downstream.
        val delivered = repository.observeShared().first { it != null }

        assertEquals(REMOTE_MODEL_ID, delivered?.model?.id)
        assertEquals(2, writes.get())
    }

    @Test
    fun `a remote model is mirrored into Room under the id it arrived with`() =
        runTest(dispatcher) {
            every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
                flowOf(remoteCustody())
            val entity = slot<CustodyModelEntity>()

            repository.observeShared().first()

            coVerifyOrder {
                custodyModelDao.deactivateAllModels()
                custodyModelDao.insertModel(capture(entity))
            }
            // The writer's id, not a freshly generated one: otherwise the two devices accumulate
            // a copy of the same schedule per sync instead of converging on one row.
            assertEquals(REMOTE_MODEL_ID, entity.captured.id)
            assertTrue(entity.captured.isActive)
            assertEquals("[0,1,2,3,4,5,6]", entity.captured.momDaysPattern)
            assertEquals(REMOTE_CREATED_AT, entity.captured.createdAt)
            assertEquals(REMOTE_MODIFIED_AT, entity.captured.lastModifiedAt)
        }

    @Test
    fun `an unchanged remote document is not written to Room again`() = runTest(dispatcher) {
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
            flowOf(remoteCustody())
        coEvery { custodyModelDao.getModelById(REMOTE_MODEL_ID) } returns mirroredEntity()

        repository.observeShared().first()

        // Firestore echoes this device's own writes straight back; re-inserting an identical
        // row would tick Room's invalidation tracker and re-emit to every observer for nothing.
        coVerify(exactly = 0) { custodyModelDao.insertModel(any()) }
        coVerify(exactly = 0) { custodyModelDao.deactivateAllModels() }
    }

    // ---- fixtures ---------------------------------------------------------

    /** Points both the one-shot and the streaming uid lookups at [partnerUid]. */
    private fun pairedWith(partnerUid: String?) {
        val me = User(
            id = MY_UID,
            email = "mom@example.com",
            name = "Mom",
            role = "mom",
            colorCode = "#FF4081",
            partnerId = partnerUid
        )
        coEvery { userRepository.getCurrentUserId() } returns MY_UID
        coEvery { userRepository.getUserById(MY_UID) } returns me
        every { userRepository.observeCurrentUserId() } returns flowOf(MY_UID)
        every { userRepository.getAllUsers() } returns flowOf(listOf(me))
    }

    private fun localModel() = CustodyModel(
        id = LOCAL_MODEL_ID,
        modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
        patternDays = 14,
        momDayIndices = (0..6).toSet(),
        startDate = START_DATE
    )

    private fun localEntity(lastModifiedAt: String) = mirroredEntity().copy(
        id = LOCAL_MODEL_ID,
        lastModifiedAt = lastModifiedAt
    )

    private fun remoteCustody(lastModifiedAt: String = REMOTE_MODIFIED_AT) = SharedCustody(
        model = localModel().copy(id = REMOTE_MODEL_ID),
        lastModifiedBy = PARTNER_UID,
        lastModifiedAt = lastModifiedAt,
        createdAt = REMOTE_CREATED_AT
    )

    /** The row [remoteCustody] mirrors to, for the echo-guard case. */
    private fun mirroredEntity() = CustodyModelEntity(
        id = REMOTE_MODEL_ID,
        modelType = "week_on_week_off",
        patternDays = 14,
        momDaysPattern = "[0,1,2,3,4,5,6]",
        startDate = START_DATE.toString(),
        isActive = true,
        repeatYearly = true,
        createdAt = REMOTE_CREATED_AT,
        lastModifiedAt = REMOTE_MODIFIED_AT
    )

    private fun permissionDenied() = FirebaseFirestoreException(
        "Missing or insufficient permissions.",
        FirebaseFirestoreException.Code.PERMISSION_DENIED
    )

    private companion object {
        const val MY_UID = "uidA"
        const val PARTNER_UID = "uidB"

        /** Sorts *before* [MY_UID], so an unsorted write is visible rather than coincidental. */
        const val EARLIER_PARTNER_UID = "uid0"

        /** What `CustodyKey.of("uidA", "uidB")` derives — pinned as a literal on purpose. */
        const val DOCUMENT_ID = "uidA__uidB"
        val PARTICIPANTS = listOf("uidA", "uidB")

        const val LOCAL_MODEL_ID = "local-model-1"
        const val REMOTE_MODEL_ID = "remote-model-1"
        const val REMOTE_CREATED_AT = "2026-07-01T09:00:00"
        const val REMOTE_MODIFIED_AT = "2026-08-04T18:30:00"

        /**
         * Ordering fixtures for the staleness guard. Absolute rather than relative to `now()`, so
         * what is asserted is the comparison under test and not the machine's clock.
         */
        const val LONG_AGO = "2020-01-01T00:00:00"
        const val RECENTLY = "2099-01-01T00:00:00"

        /** `RETRY_BASE_MS shl 0`, the repository's first backoff step. */
        const val FIRST_BACKOFF_MS = 1_000L

        val START_DATE: LocalDate = LocalDate.of(2026, 8, 3)
    }
}
