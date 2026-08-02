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
import org.junit.Before
import org.junit.Test

/**
 * [HomeViewModel.paired] is the one flow in this class fed by a realtime repository
 * (the rest are Room-backed), so it is the one worth pinning: it must track
 * [PairingRepository.observePairingState] exactly, treating [PairingState.Loading] as
 * "not paired" both on cold start and if the underlying listener recovers into it later.
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
            homeIdentityDependencies = HomeIdentityDependencies(userRepository, pairingRepository)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `paired follows the repository and treats Loading as not paired`() = runTest(dispatcher) {
        viewModel.paired.test {
            // stateIn's own initial value (false) and the repository's Loading state both
            // map to false, so this is a single conflated item, not two.
            assertEquals(false, awaitItem())

            pairingState.value = PairingState.Paired(
                PartnerSummary(id = "partner-1", name = "Alex", email = "alex@example.com", pairedSinceMillis = null)
            )
            assertEquals(true, awaitItem())

            // A permanent listener failure recovers to Loading (per observePairingState's
            // contract) — the CTA must come back rather than staying hidden behind a stale
            // "paired" reading.
            pairingState.value = PairingState.Loading
            assertEquals(false, awaitItem())

            // NotPaired is a second, distinct "not paired" reason but maps to the same
            // boolean, so StateFlow conflates it and there is nothing new to await.
            pairingState.value = PairingState.NotPaired()
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
