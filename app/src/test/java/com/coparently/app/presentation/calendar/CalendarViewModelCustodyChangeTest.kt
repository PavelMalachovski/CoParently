package com.coparently.app.presentation.calendar

import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.presentation.common.NamedParent
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the wiring behind the "schedule changed under you" banner:
 * [CalendarViewModel.custodyChangeAnnouncement] and [CalendarViewModel.dismissCustodyChange].
 *
 * The decision itself — is there a change to announce, and whose is it — is covered on its own,
 * with no ViewModel involved, in `CustodyChangeAnnouncementTest`. These tests exist only to prove
 * the ViewModel actually feeds that decision the right inputs (this device's own uid from
 * [ParentsSource], the persisted dismissal from [EncryptedPreferences]) and that dismissal
 * actually persists.
 *
 * [ParentsSource] is mocked directly rather than built through the real `testParentsSource()`
 * fixture: the real class shares its stream through `shareIn` on its own `Dispatchers.Default`
 * scope, which is not governed by the virtual clock these tests advance and would make an
 * assertion on `custodyChangeAnnouncement` race a real background thread. `ParentsSourceTest`
 * already covers that class in isolation; here it is a collaborator, not the thing under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelCustodyChangeTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var custodyScheduleDao: CustodyScheduleDao
    private lateinit var encryptedPreferences: EncryptedPreferences
    private lateinit var sharedCustody: MutableStateFlow<SharedCustody?>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        custodyScheduleDao = mockk {
            every { getAllActiveSchedules() } returns flowOf(emptyList())
        }
        encryptedPreferences = mockk {
            every { getString(any(), any()) } returns null
            every { getString(any()) } returns null
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { putString(any(), any()) } just Runs
            every { putBoolean(any(), any()) } just Runs
        }
        sharedCustody = MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    /**
     * Builds a [CalendarViewModel] signed in as [uid], and starts a background collector on
     * [CalendarViewModel.custodyChangeAnnouncement].
     *
     * That collector is not incidental: the property is `WhileSubscribed` (see its doc), so
     * without an active collector it never subscribes to `observeShared()`/`parents` at all and
     * `.value` would never move off its initial `null` — exactly the "nobody is watching, so
     * don't hold the listener open" behaviour the property exists for, and exactly why a real
     * screen's `collectAsState()` is what normally provides that collector.
     * [TestScope.backgroundScope] is cancelled automatically at the end of each test.
     */
    private fun TestScope.viewModelSignedInAs(uid: String): CalendarViewModel {
        val custodyModelRepository = mockk<CustodyModelRepository> {
            every { getActiveModel() } returns flowOf(null)
            every { observeShared() } returns sharedCustody
        }
        val parentsSource = mockk<ParentsSource> {
            every { observe() } returns flowOf(
                Parents(me = NamedParent(uid = uid, slot = "mom", name = "Me"), loaded = true)
            )
        }
        val viewModel = CalendarViewModel(
            custodyScheduleDao,
            custodyModelRepository,
            encryptedPreferences,
            parentsSource
        )
        backgroundScope.launch { viewModel.custodyChangeAnnouncement.collect {} }
        return viewModel
    }

    @Test
    fun `a remote change by the co-parent is announced`() = runTest(testDispatcher) {
        val viewModel = viewModelSignedInAs(MY_UID)
        advanceUntilIdle()

        val change = custodyOf(lastModifiedBy = CO_PARENT_UID)
        sharedCustody.value = change
        advanceUntilIdle()

        assertEquals(change, viewModel.custodyChangeAnnouncement.value)
    }

    @Test
    fun `my own write is not announced`() = runTest(testDispatcher) {
        val viewModel = viewModelSignedInAs(MY_UID)
        advanceUntilIdle()

        sharedCustody.value = custodyOf(lastModifiedBy = MY_UID)
        advanceUntilIdle()

        assertNull(viewModel.custodyChangeAnnouncement.value)
    }

    @Test
    fun `a lastModifiedBy matching neither parent is still announced`() = runTest(testDispatcher) {
        val viewModel = viewModelSignedInAs(MY_UID)
        advanceUntilIdle()

        val change = custodyOf(lastModifiedBy = "some-stranger-uid")
        sharedCustody.value = change
        advanceUntilIdle()

        assertEquals(change, viewModel.custodyChangeAnnouncement.value)
    }

    @Test
    fun `dismissing persists and hides the change`() = runTest(testDispatcher) {
        val viewModel = viewModelSignedInAs(MY_UID)
        advanceUntilIdle()
        val change = custodyOf(lastModifiedBy = CO_PARENT_UID)
        sharedCustody.value = change
        advanceUntilIdle()
        assertEquals(change, viewModel.custodyChangeAnnouncement.value)

        viewModel.dismissCustodyChange(change.lastModifiedAt)
        advanceUntilIdle()

        assertNull(viewModel.custodyChangeAnnouncement.value)
        verify {
            encryptedPreferences.putString(PreferenceKeys.DISMISSED_CUSTODY_CHANGE_AT, change.lastModifiedAt)
        }
    }

    @Test
    fun `a later change with a different lastModifiedAt is announced again after dismissal`() =
        runTest(testDispatcher) {
            val viewModel = viewModelSignedInAs(MY_UID)
            advanceUntilIdle()
            val firstChange = custodyOf(lastModifiedBy = CO_PARENT_UID, lastModifiedAt = "2026-08-05T09:00:00")
            sharedCustody.value = firstChange
            advanceUntilIdle()
            viewModel.dismissCustodyChange(firstChange.lastModifiedAt)
            advanceUntilIdle()
            assertNull(viewModel.custodyChangeAnnouncement.value)

            val secondChange = custodyOf(lastModifiedBy = CO_PARENT_UID, lastModifiedAt = "2026-08-06T10:00:00")
            sharedCustody.value = secondChange
            advanceUntilIdle()

            assertEquals(secondChange, viewModel.custodyChangeAnnouncement.value)
        }

    @Test
    fun `a dismissal persisted from a previous process is honoured from start-up`() = runTest(testDispatcher) {
        val change = custodyOf(lastModifiedBy = CO_PARENT_UID)
        every {
            encryptedPreferences.getString(PreferenceKeys.DISMISSED_CUSTODY_CHANGE_AT)
        } returns change.lastModifiedAt
        sharedCustody.value = change

        val viewModel = viewModelSignedInAs(MY_UID)
        advanceUntilIdle()

        assertNull(viewModel.custodyChangeAnnouncement.value)
    }

    private fun custodyOf(
        lastModifiedBy: String,
        lastModifiedAt: String = MODIFIED_AT
    ) = SharedCustody(
        model = CustodyModel(
            id = "model-1",
            modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
            patternDays = 14,
            momDayIndices = (0..6).toSet(),
            startDate = LocalDate.of(2026, 1, 1)
        ),
        lastModifiedBy = lastModifiedBy,
        lastModifiedAt = lastModifiedAt,
        createdAt = "2026-01-01T00:00:00"
    )

    private companion object {
        const val MY_UID = "my-uid"
        const val CO_PARENT_UID = "co-parent-uid"
        const val MODIFIED_AT = "2026-08-05T12:00:00"
    }
}
