package com.coparently.app.presentation.calendar

import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.custody.CustodyTimestamp
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
            every { observeDayOverrides() } returns flowOf(emptyMap())
        }
        val parentsSource = mockk<ParentsSource> {
            every { observe() } returns flowOf(
                Parents(me = NamedParent(uid = uid, slot = "mom", name = "Me"), loaded = true)
            )
        }
        val viewModel = CalendarViewModel(
            custodyScheduleDao = custodyScheduleDao,
            custodyModelRepository = custodyModelRepository,
            encryptedPreferences = encryptedPreferences,
            friendRepository = noCalendarFriends(),
            parentsSource = parentsSource
        )
        backgroundScope.launch { viewModel.custodyChangeAnnouncement.collect {} }
        return viewModel
    }

    /**
     * Builds a [CalendarViewModel] whose [ParentsSource.observe] is [parentsFlow], so a test can
     * control exactly when `parents` resolves relative to `sharedCustody` — the ordering that
     * [CustodyChangeAnnouncement.toAnnounce][com.coparently.app.domain.custody.CustodyChangeAnnouncement.toAnnounce]'s
     * `parentsLoaded` gate exists to get right.
     */
    private fun TestScope.viewModelWithParents(parentsFlow: MutableStateFlow<Parents>): CalendarViewModel {
        val custodyModelRepository = mockk<CustodyModelRepository> {
            every { getActiveModel() } returns flowOf(null)
            every { observeShared() } returns sharedCustody
            every { observeDayOverrides() } returns flowOf(emptyMap())
        }
        val parentsSource = mockk<ParentsSource> {
            every { observe() } returns parentsFlow
        }
        val viewModel = CalendarViewModel(
            custodyScheduleDao = custodyScheduleDao,
            custodyModelRepository = custodyModelRepository,
            encryptedPreferences = encryptedPreferences,
            friendRepository = noCalendarFriends(),
            parentsSource = parentsSource
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

        viewModel.dismissCustodyChange(change.lastModifiedAtMillis)
        advanceUntilIdle()

        assertNull(viewModel.custodyChangeAnnouncement.value)
        verify {
            encryptedPreferences.putString(
                PreferenceKeys.DISMISSED_CUSTODY_CHANGE_AT,
                change.lastModifiedAtMillis.toString()
            )
        }
    }

    @Test
    fun `a later change with a different instant is announced again after dismissal`() =
        runTest(testDispatcher) {
            val viewModel = viewModelSignedInAs(MY_UID)
            advanceUntilIdle()
            val firstChange = custodyOf(
                lastModifiedBy = CO_PARENT_UID,
                lastModifiedAtMillis = CustodyTimestamp.fromWire("2026-08-05T09:00:00")
            )
            sharedCustody.value = firstChange
            advanceUntilIdle()
            viewModel.dismissCustodyChange(firstChange.lastModifiedAtMillis)
            advanceUntilIdle()
            assertNull(viewModel.custodyChangeAnnouncement.value)

            val secondChange = custodyOf(
                lastModifiedBy = CO_PARENT_UID,
                lastModifiedAtMillis = CustodyTimestamp.fromWire("2026-08-06T10:00:00")
            )
            sharedCustody.value = secondChange
            advanceUntilIdle()

            assertEquals(secondChange, viewModel.custodyChangeAnnouncement.value)
        }

    @Test
    fun `a dismissal persisted from a previous process is honoured from start-up`() = runTest(testDispatcher) {
        val change = custodyOf(lastModifiedBy = CO_PARENT_UID)
        every {
            encryptedPreferences.getString(PreferenceKeys.DISMISSED_CUSTODY_CHANGE_AT)
        } returns change.lastModifiedAtMillis.toString()
        sharedCustody.value = change

        val viewModel = viewModelSignedInAs(MY_UID)
        advanceUntilIdle()

        assertNull(viewModel.custodyChangeAnnouncement.value)
    }

    @Test
    fun `nothing is announced before parents has loaded, then the change surfaces once it does`() =
        runTest(testDispatcher) {
            // Parents starts every fresh subscription from a synthetic "nobody is known yet".
            // CustodyModelRepository's pair resolution is Room-only and reliably faster than
            // Parents resolving three Firestore pairing listeners, so a real change can arrive
            // before this device's own uid is known.
            val parentsFlow = MutableStateFlow(Parents())
            val viewModel = viewModelWithParents(parentsFlow)
            advanceUntilIdle()

            val change = custodyOf(lastModifiedBy = CO_PARENT_UID)
            sharedCustody.value = change
            advanceUntilIdle()
            assertNull(viewModel.custodyChangeAnnouncement.value)

            parentsFlow.value = Parents(
                me = NamedParent(uid = MY_UID, slot = "mom", name = "Me"),
                coParent = NamedParent(uid = CO_PARENT_UID, slot = "dad", name = "Co-parent"),
                loaded = true
            )
            advanceUntilIdle()

            assertEquals(change, viewModel.custodyChangeAnnouncement.value)
        }

    @Test
    fun `an echo of this device's own write is never announced, even while parents is still loading`() =
        runTest(testDispatcher) {
            // The failure this test pins: before the parentsLoaded gate, a null myUid read as
            // "definitely not mine" would announce the user's own edit as the co-parent's the
            // instant the echo of their own write arrived ahead of Parents resolving.
            val parentsFlow = MutableStateFlow(Parents())
            val viewModel = viewModelWithParents(parentsFlow)
            advanceUntilIdle()

            val change = custodyOf(lastModifiedBy = MY_UID)
            sharedCustody.value = change
            advanceUntilIdle()
            assertNull(viewModel.custodyChangeAnnouncement.value)

            parentsFlow.value = Parents(me = NamedParent(uid = MY_UID, slot = "mom", name = "Me"), loaded = true)
            advanceUntilIdle()

            assertNull(viewModel.custodyChangeAnnouncement.value)
        }

    private fun custodyOf(
        lastModifiedBy: String,
        lastModifiedAtMillis: Long = MODIFIED_AT
    ) = SharedCustody(
        model = CustodyModel(
            id = "model-1",
            modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
            patternDays = 14,
            momDayIndices = (0..6).toSet(),
            startDate = LocalDate.of(2026, 1, 1)
        ),
        lastModifiedBy = lastModifiedBy,
        lastModifiedAtMillis = lastModifiedAtMillis,
        createdAt = "2026-01-01T00:00:00"
    )

    private companion object {
        const val MY_UID = "my-uid"
        const val CO_PARENT_UID = "co-parent-uid"
        val MODIFIED_AT = CustodyTimestamp.fromWire("2026-08-05T12:00:00")
    }
}
