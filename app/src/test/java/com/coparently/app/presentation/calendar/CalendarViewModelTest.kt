package com.coparently.app.presentation.calendar

import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.presentation.common.testParentsSource
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for CalendarViewModel.
 * Covers view mode, date selection, parent filter and event type filters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var custodyScheduleDao: CustodyScheduleDao
    private lateinit var custodyModelRepository: CustodyModelRepository
    private lateinit var encryptedPreferences: EncryptedPreferences
    private lateinit var viewModel: CalendarViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        custodyScheduleDao = mockk {
            every { getAllActiveSchedules() } returns flowOf(emptyList())
        }
        custodyModelRepository = mockk {
            every { getActiveModel() } returns flowOf(null)
            every { observeShared() } returns flowOf(null)
        }
        encryptedPreferences = mockk {
            every { getString(any(), any()) } returns null
            every { getString(any()) } returns null
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { putString(any(), any()) } just Runs
            every { putBoolean(any(), any()) } just Runs
        }
        viewModel = CalendarViewModel(
            custodyScheduleDao = custodyScheduleDao,
            custodyModelRepository = custodyModelRepository,
            encryptedPreferences = encryptedPreferences,
            friendRepository = noCalendarFriends(),
            parentsSource = testParentsSource()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `default view mode is month`() = runTest {
        assertEquals(CalendarViewMode.MONTH, viewModel.viewMode.value)
    }

    @Test
    fun `setViewMode updates state`() = runTest {
        viewModel.setViewMode(CalendarViewMode.WEEK)
        assertEquals(CalendarViewMode.WEEK, viewModel.viewMode.value)

        viewModel.setViewMode(CalendarViewMode.DAY)
        assertEquals(CalendarViewMode.DAY, viewModel.viewMode.value)
    }

    @Test
    fun `setSelectedDate updates state`() = runTest {
        val date = LocalDate.of(2026, 7, 20)
        viewModel.setSelectedDate(date)
        assertEquals(date, viewModel.selectedDate.value)
    }

    @Test
    fun `setSelectedDate moves the displayed month to the picked day`() = runTest {
        val otherMonthDay = YearMonth.now().plusMonths(2).atDay(5)
        viewModel.setSelectedDate(otherMonthDay)
        assertEquals(YearMonth.from(otherMonthDay), viewModel.displayedMonth.value)
    }

    @Test
    fun `showMonth clears the selection for a month that is not today's`() = runTest {
        val nextMonth = YearMonth.now().plusMonths(1)
        viewModel.showMonth(nextMonth)
        assertEquals(nextMonth, viewModel.displayedMonth.value)
        assertNull(viewModel.selectedDate.value)
    }

    @Test
    fun `showMonth re-selects today when paging back to today's month`() = runTest {
        viewModel.showMonth(YearMonth.now().plusMonths(1))
        viewModel.showMonth(YearMonth.now())
        assertEquals(LocalDate.now(), viewModel.selectedDate.value)
    }

    @Test
    fun `default parent filter shows both parents`() = runTest {
        assertEquals(ParentFilter.BOTH, viewModel.parentFilter.value)
    }

    @Test
    fun `setParentFilter switches views`() = runTest {
        viewModel.setParentFilter(ParentFilter.MOM)
        assertEquals(ParentFilter.MOM, viewModel.parentFilter.value)

        viewModel.setParentFilter(ParentFilter.DAD)
        assertEquals(ParentFilter.DAD, viewModel.parentFilter.value)
    }

    @Test
    fun `toggleEventTypeVisibility hides and shows a type`() = runTest {
        assertTrue(viewModel.hiddenEventTypes.value.isEmpty())

        viewModel.toggleEventTypeVisibility("school")
        assertTrue("school" in viewModel.hiddenEventTypes.value)

        viewModel.toggleEventTypeVisibility("school")
        assertFalse("school" in viewModel.hiddenEventTypes.value)
    }

    @Test
    fun `addCustomEventType normalizes and deduplicates`() = runTest {
        viewModel.addCustomEventType("  Music Lessons ")
        assertEquals(listOf("music lessons"), viewModel.customEventTypes.value)

        // Duplicate and default types are ignored
        viewModel.addCustomEventType("music lessons")
        viewModel.addCustomEventType("school")
        viewModel.addCustomEventType("   ")
        assertEquals(listOf("music lessons"), viewModel.customEventTypes.value)
    }

    @Test
    fun `holidays are shown by default and can be disabled`() = runTest {
        assertTrue(viewModel.showHolidays.value)

        viewModel.setShowHolidays(false)
        assertFalse(viewModel.showHolidays.value)
    }

    @Test
    fun `the query anchor starts on the current month`() = runTest {
        assertEquals(YearMonth.now(), viewModel.queryAnchorMonth.value)
    }

    @Test
    fun `paging within tolerance leaves the query anchor alone`() = runTest {
        val start = YearMonth.now()
        viewModel.showMonth(start.plusMonths(1))
        viewModel.showMonth(start.plusMonths(2))
        assertEquals(start, viewModel.queryAnchorMonth.value)
        assertEquals(start.plusMonths(2), viewModel.displayedMonth.value)
    }

    @Test
    fun `paging past tolerance re-anchors the query`() = runTest {
        val start = YearMonth.now()
        viewModel.showMonth(start.plusMonths(3))
        assertEquals(start.plusMonths(3), viewModel.queryAnchorMonth.value)
    }

    @Test
    fun `paging backwards past tolerance re-anchors the query`() = runTest {
        val start = YearMonth.now()
        viewModel.showMonth(start.minusMonths(3))
        assertEquals(start.minusMonths(3), viewModel.queryAnchorMonth.value)
    }

    @Test
    fun `tapping a day in a distant month re-anchors the query`() = runTest {
        val start = YearMonth.now()
        val distant = start.plusMonths(7).atDay(14)
        viewModel.setSelectedDate(distant)
        assertEquals(YearMonth.from(distant), viewModel.queryAnchorMonth.value)
    }

    /**
     * The general property the sticky window rests on, asserted after every step rather than at
     * four specific call sites: whatever moved the grid, the anchor may never be further than
     * [CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS] from it. `CalendarQueryRangeTest` proves
     * the window covers the grid *given* that bound; without this, a future method writing
     * `_displayedMonth` past `moveTo` would un-load months with every other test still green,
     * and the symptom is a silently empty grid.
     */
    @Test
    fun `the query anchor never drifts past tolerance however the grid is moved`() = runTest {
        val start = YearMonth.now()
        val steps: List<Pair<String, () -> Unit>> = listOf(
            // small steps forward
            "page +1" to { viewModel.showMonth(start.plusMonths(1)) },
            "page +2" to { viewModel.showMonth(start.plusMonths(2)) },
            "page +3" to { viewModel.showMonth(start.plusMonths(3)) },
            "page +4" to { viewModel.showMonth(start.plusMonths(4)) },
            // small steps backward, back through the origin
            "page +3 again" to { viewModel.showMonth(start.plusMonths(3)) },
            "page +2 again" to { viewModel.showMonth(start.plusMonths(2)) },
            "page +1 again" to { viewModel.showMonth(start.plusMonths(1)) },
            "page 0" to { viewModel.showMonth(start) },
            "page -1" to { viewModel.showMonth(start.minusMonths(1)) },
            "page -2" to { viewModel.showMonth(start.minusMonths(2)) },
            "page -3" to { viewModel.showMonth(start.minusMonths(3)) },
            // far jumps in both directions
            "jump +14" to { viewModel.showMonth(start.plusMonths(14)) },
            "jump -20" to { viewModel.showMonth(start.minusMonths(20)) },
            "back to today" to { viewModel.showMonth(start) },
            // across a year boundary, one month at a time
            "to December" to { viewModel.showMonth(YearMonth.of(start.year, 12)) },
            "to January" to { viewModel.showMonth(YearMonth.of(start.year + 1, 1)) },
            "to February" to { viewModel.showMonth(YearMonth.of(start.year + 1, 2)) },
            "back to December" to { viewModel.showMonth(YearMonth.of(start.year, 12)) },
            // day taps, which move the grid too
            "tap a day next month" to { viewModel.setSelectedDate(start.plusMonths(1).atDay(9)) },
            "tap a day far ahead" to { viewModel.setSelectedDate(start.plusMonths(9).atDay(28)) },
            "tap a day far behind" to { viewModel.setSelectedDate(start.minusMonths(11).atDay(1)) },
            "tap a day a year back" to { viewModel.setSelectedDate(start.minusMonths(12).atDay(15)) }
        )

        for ((label, step) in steps) {
            step()
            val drift = kotlin.math.abs(
                java.time.temporal.ChronoUnit.MONTHS.between(
                    viewModel.queryAnchorMonth.value,
                    viewModel.displayedMonth.value
                )
            )
            assertTrue(
                drift <= CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS,
                "after '$label' the anchor ${viewModel.queryAnchorMonth.value} drifted $drift " +
                    "months from the displayed ${viewModel.displayedMonth.value}"
            )
        }
    }
}
