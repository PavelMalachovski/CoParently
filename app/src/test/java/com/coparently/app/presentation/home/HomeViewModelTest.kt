package com.coparently.app.presentation.home

import app.cash.turbine.test
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.FamilyKindSource
import com.coparently.app.presentation.common.ParentsSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HomeViewModel.uiState] is fed by the one realtime repository in this class (the rest are
 * Room-backed), so it is the one worth pinning. Three states, and which one is wrong to show is
 * the whole point:
 *
 * - [PairingState.Loading] means *unknown*, and the page says so. It used to collapse into "not
 *   paired", which made an existing user's launch splash -> a full screen asking them to add the
 *   co-parent they already have -> the dashboard.
 * - Unknown for longer than the settle window falls back to the invitation, because
 *   `observePairingState` also recovers into `Loading` when its listener fails permanently, and
 *   a page that waits forever is worse than one that offers something to do.
 * - While not paired, the page must expose the invitation and *nothing else*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var pairingState: MutableStateFlow<PairingState>
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        val eventRepository = mockk<EventRepository> {
            every { getAllEvents() } returns flowOf(emptyList())
            every { getEventsByDateRange(any(), any()) } returns flowOf(emptyList())
        }
        val changeRequestRepository = mockk<ChangeRequestRepository> {
            every { getAllChangeRequests() } returns flowOf(emptyList())
        }
        val custodyModelRepository = mockk<CustodyModelRepository> {
            every { getActiveModel() } returns flowOf(null)
            // The awaiting-swaps flow (items 4/13) subscribes at construction.
            every { observeDayOverrides() } returns flowOf(emptyMap())
        }
        val expenseRepository = mockk<ExpenseRepository> {
            every { getAllExpenses() } returns flowOf(emptyList())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { getDefaultCurrencyFlow() } returns flowOf(SupportedCurrency.DEFAULT)
        }
        val messageRepository = mockk<MessageRepository>(relaxed = true)
        val userRepository = mockk<UserRepository> {
            coEvery { getCurrentUser() } returns null
            coEvery { getCurrentUserId() } returns null
            // The refreshed dashboard attributes spend per parent, so construction now reads
            // the user list to build its uid -> "mom"/"dad" map.
            every { getAllUsers() } returns flowOf(emptyList())
            every { observeCurrentUserId() } returns flowOf(null)
        }
        pairingState = MutableStateFlow(PairingState.Loading)
        val pairingRepository = mockk<PairingRepository> {
            every { observePairingState() } returns pairingState
        }

        viewModel = HomeViewModel(
            eventRepository = eventRepository,
            changeRequestRepository = changeRequestRepository,
            custodyModelRepository = custodyModelRepository,
            monthSpendDependencies = MonthSpendDependencies(expenseRepository, preferencesRepository),
            messageRepository = messageRepository,
            familyKindSource = FamilyKindSource(userRepository, pairingRepository),
            homeIdentityDependencies = HomeIdentityDependencies(
                userRepository,
                pairingRepository,
                ParentsSource(userRepository, pairingRepository)
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the page is the invitation alone until a co-parent is linked`() = runTest(dispatcher) {
        viewModel.uiState.test {
            // `stateIn`'s initial value and the repository's `Loading` both map to Loading, so
            // this is a single conflated item, not two. Nothing is known yet, so the page
            // asserts neither "you have a co-parent" nor "you have none".
            assertEquals(HomeUiState.Loading, awaitItem())

            // No answer ever arrives. After the settle timeout the page stops waiting and shows
            // the invitation — a skeleton is right for a listener that is slow and wrong for one
            // that has failed, and `Loading` is what the repository falls back to in both cases.
            assertEquals(HomeUiState.AskForCoParent, awaitItem())

            pairingState.value = PairingState.Paired(
                PartnerSummary(id = "partner-1", name = "Alex", email = "alex@example.com", pairedSinceMillis = null)
            )
            // Everything comes back at once, because the page is one value rather than eight
            // the composable recombines.
            val dashboard = awaitItem()
            assertTrue(dashboard is HomeUiState.Dashboard)

            // A permanent listener failure recovers to Loading (per observePairingState's
            // contract) — the invitation must come back rather than staying hidden behind a
            // stale "paired" reading. The settle window is long past by now, so this does not
            // send the page back to a skeleton it would never leave.
            pairingState.value = PairingState.Loading
            assertEquals(HomeUiState.AskForCoParent, awaitItem())

            // NotPaired is a second, distinct "not paired" reason but maps to the same state,
            // so StateFlow conflates it and there is nothing new to await.
            pairingState.value = PairingState.NotPaired()
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a paired parent is never asked to pair while the answer is still unknown`() =
        runTest(dispatcher) {
            // UX-1. `PairingState.Loading` used to collapse into "not paired", so an existing
            // user's launch was splash -> a full screen saying "add your co-parent" -> the
            // dashboard. The answer arriving before the settle timeout is the normal case, and
            // the invitation must not appear at all on the way through.
            viewModel.uiState.test {
                assertEquals(HomeUiState.Loading, awaitItem())

                pairingState.value = PairingState.Paired(
                    PartnerSummary(
                        id = "partner-1",
                        name = "Alex",
                        email = "alex@example.com",
                        pairedSinceMillis = null
                    )
                )

                assertTrue(awaitItem() is HomeUiState.Dashboard)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an unpaired page carries no handover, tiles, week or changes`() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())

            pairingState.value = PairingState.NotPaired()
            val state = awaitItem()
            // Not "the dashboard's fields are empty" — the dashboard is not there at all.
            // Emptiness was the old bug: a hero with nothing to hand over, two tiles reading
            // zero and two empty feeds, arranged around the card that says the thing that
            // would fill them.
            assertEquals(HomeUiState.AskForCoParent, state)
            assertFalse(state is HomeUiState.Dashboard)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
