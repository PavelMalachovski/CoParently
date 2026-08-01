package com.coparently.app.presentation.pairing

import app.cash.turbine.test
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.PairingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PairingRepository
    private lateinit var viewModel: PairingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        coEvery { repository.observePairingState() } returns
            flowOf(PairingState.NotPaired())
        viewModel = PairingViewModel(
            pairingRepository = repository,
            qrCodeService = mockk<QRCodeService>(relaxed = true),
            analyticsManager = mockk<AnalyticsManager>(relaxed = true)
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `code input is upper-cased and trimmed to the code length`() = runTest(dispatcher) {
        viewModel.onCodeInputChange("4f7k2mXX")

        assertEquals("4F7K2M", viewModel.form.value.codeInput)
    }

    @Test
    fun `redeeming a short code does not hit the repository`() = runTest(dispatcher) {
        viewModel.onCodeInputChange("4F7")
        viewModel.redeemCode()

        coVerify(exactly = 0) { repository.redeem(any()) }
    }

    @Test
    fun `an already-paired failure maps to its own message`() = runTest(dispatcher) {
        coEvery { repository.redeem("4F7K2M") } returns
            Result.failure(PairingException(PairingError.AlreadyPaired))

        viewModel.onCodeInputChange("4F7K2M")
        viewModel.redeemCode()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.pairing_error_already_paired, viewModel.form.value.errorRes)
    }

    @Test
    fun `state mirrors the repository`() = runTest(dispatcher) {
        viewModel.state.test {
            // stateIn's initial value is always replayed to a brand-new collector before
            // SharingStarted.WhileSubscribed can start collecting the repository's flow —
            // there is no subscriber yet for it to react to until this very collect() call.
            assertEquals(PairingState.Loading, awaitItem())
            assertEquals(PairingState.NotPaired(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
