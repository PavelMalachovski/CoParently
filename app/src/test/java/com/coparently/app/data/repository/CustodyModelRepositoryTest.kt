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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
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
 * user touches Firestore not at all; and the listener *keeps delivering after a failure* —
 * the defect `MessageRepositoryImpl` still ships, where a terminal `catch` completes the mirror
 * flow and leaves the feature running on Room alone for the rest of the process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustodyModelRepositoryTest {

    private lateinit var custodyModelDao: CustodyModelDao
    private lateinit var userRepository: UserRepository
    private lateinit var firestoreCustodyDataSource: FirestoreCustodyDataSource
    private lateinit var repository: CustodyModelRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        custodyModelDao = mockk(relaxed = true)
        userRepository = mockk()
        firestoreCustodyDataSource = mockk()
        repository = CustodyModelRepository(
            custodyModelDao, userRepository, firestoreCustodyDataSource
        )

        pairedWith(PARTNER_UID)
        coEvery { custodyModelDao.getModelById(any()) } returns null
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
    fun `saving writes Room before Firestore`() = runTest {
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
        // firestore.rules stores participants pre-sorted and compares them order-sensitively on
        // update, so an unsorted array would deny every later write forever.
        assertEquals(listOf(MY_UID, PARTNER_UID), PARTICIPANTS)
        assertEquals(MY_UID, custody.captured.lastModifiedBy)
        assertEquals(LOCAL_MODEL_ID, custody.captured.model.id)
    }

    @Test
    fun `a Firestore failure on save leaves the local model in place`() = runTest {
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
    fun `an unpaired user saves to Room and writes no document`() = runTest {
        pairedWith(partnerUid = null)
        val entity = slot<CustodyModelEntity>()

        repository.saveAndActivate(localModel())

        coVerify(exactly = 1) { custodyModelDao.insertModel(capture(entity)) }
        assertEquals(LOCAL_MODEL_ID, entity.captured.id)
        coVerify(exactly = 0) { firestoreCustodyDataSource.setCustody(any(), any(), any()) }
    }

    @Test
    fun `an update preserves the createdAt already on the document`() = runTest {
        coEvery { firestoreCustodyDataSource.getCustody(DOCUMENT_ID) } returns remoteCustody()
        val custody = slot<SharedCustody>()
        coEvery {
            firestoreCustodyDataSource.setCustody(any(), any(), capture(custody))
        } returns Unit

        repository.saveAndActivate(localModel())

        // Editing the pattern must not re-date the pair's arrangement.
        assertEquals(REMOTE_CREATED_AT, custody.captured.createdAt)
    }

    // ---- reading ----------------------------------------------------------

    @Test
    fun `an unpaired user has no shared document`() = runTest {
        pairedWith(partnerUid = null)

        assertNull(repository.getShared())
        assertNull(repository.sharedUpstream().first())

        coVerify(exactly = 0) { firestoreCustodyDataSource.getCustody(any()) }
        coVerify(exactly = 0) { firestoreCustodyDataSource.observeCustody(any()) }
    }

    @Test
    fun `the one-shot read reaches the document derived from the two uids`() = runTest {
        coEvery { firestoreCustodyDataSource.getCustody(DOCUMENT_ID) } returns remoteCustody()

        val shared = repository.getShared()

        assertEquals(REMOTE_MODEL_ID, shared?.model?.id)
        assertEquals(PARTNER_UID, shared?.lastModifiedBy)
    }

    @Test
    fun `a failed one-shot read degrades to null rather than propagating`() = runTest {
        coEvery { firestoreCustodyDataSource.getCustody(DOCUMENT_ID) } throws permissionDenied()

        assertNull(repository.getShared())
    }

    @Test
    fun `the observer keeps delivering after a failure`() = runTest {
        val collections = AtomicInteger()
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns flow {
            if (collections.getAndIncrement() == 0) throw permissionDenied()
            emit(remoteCustody())
        }

        // Fails — the exception escapes and the test errors out — without `retryWhen` on the
        // remote branch. A terminal `catch` instead would make it fail differently and worse:
        // the flow would complete having emitted nothing, silently, forever.
        val delivered = repository.sharedUpstream().first { it != null }

        assertEquals(REMOTE_MODEL_ID, delivered?.model?.id)
        assertEquals(2, collections.get())
    }

    @Test
    fun `a remote model is mirrored into Room under the id it arrived with`() = runTest {
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
            flowOf(remoteCustody())
        val entity = slot<CustodyModelEntity>()

        repository.sharedUpstream().toList()

        coVerifyOrder {
            custodyModelDao.deactivateAllModels()
            custodyModelDao.insertModel(capture(entity))
        }
        // The writer's id, not a freshly generated one: otherwise the two devices accumulate a
        // copy of the same schedule per sync instead of converging on one row.
        assertEquals(REMOTE_MODEL_ID, entity.captured.id)
        assertTrue(entity.captured.isActive)
        assertEquals("[0,1,2,3,4,5,6]", entity.captured.momDaysPattern)
        assertEquals(REMOTE_CREATED_AT, entity.captured.createdAt)
        assertEquals(REMOTE_MODIFIED_AT, entity.captured.lastModifiedAt)
    }

    @Test
    fun `an unchanged remote document is not written to Room again`() = runTest {
        every { firestoreCustodyDataSource.observeCustody(DOCUMENT_ID) } returns
            flowOf(remoteCustody())
        coEvery { custodyModelDao.getModelById(REMOTE_MODEL_ID) } returns mirroredEntity()

        repository.sharedUpstream().toList()

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

    private fun remoteCustody() = SharedCustody(
        model = localModel().copy(id = REMOTE_MODEL_ID),
        lastModifiedBy = PARTNER_UID,
        lastModifiedAt = REMOTE_MODIFIED_AT,
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

        /** What `CustodyKey.of("uidA", "uidB")` derives — pinned as a literal on purpose. */
        const val DOCUMENT_ID = "uidA__uidB"
        val PARTICIPANTS = listOf("uidA", "uidB")

        const val LOCAL_MODEL_ID = "local-model-1"
        const val REMOTE_MODEL_ID = "remote-model-1"
        const val REMOTE_CREATED_AT = "2026-07-01T09:00:00"
        const val REMOTE_MODIFIED_AT = "2026-08-04T18:30:00"
        val START_DATE: LocalDate = LocalDate.of(2026, 8, 3)
    }
}
