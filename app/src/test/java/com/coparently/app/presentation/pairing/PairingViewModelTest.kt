package com.coparently.app.presentation.pairing

import app.cash.turbine.test
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.data.repository.ParentSlotMigrator
import com.coparently.app.data.session.SignedInAccountSource
import com.coparently.app.domain.model.AccountSummary
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.utils.ValidationResult
import com.coparently.app.utils.ValidationUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PairingRepository
    private lateinit var analyticsManager: AnalyticsManager
    private lateinit var userRepository: UserRepository
    private lateinit var parentSlotMigrator: ParentSlotMigrator
    private lateinit var signedInAccountSource: SignedInAccountSource
    private lateinit var viewModel: PairingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // ValidationUtils.validateEmail() reads android.util.Patterns.EMAIL_ADDRESS, an
        // Android framework field that is null on the plain-JVM unit-test classpath
        // (isReturnDefaultValues only helps for stubbed methods, not a static field read).
        // Mocking the object sidesteps that entirely; each email test stubs the one call
        // it needs.
        mockkObject(ValidationUtils)
        repository = mockk(relaxed = true)
        analyticsManager = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        parentSlotMigrator = mockk(relaxed = true)
        coEvery { repository.observePairingState() } returns
            flowOf(PairingState.NotPaired())
        signedInAccountSource = mockk(relaxed = true)
        every { signedInAccountSource.observe() } returns flowOf(ACCOUNT)
        viewModel = PairingViewModel(
            pairingRepository = repository,
            qrCodeService = mockk<QRCodeService>(relaxed = true),
            analyticsManager = analyticsManager,
            userRepository = userRepository,
            parentSlotMigrator = parentSlotMigrator,
            signedInAccountSource = signedInAccountSource
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(ValidationUtils)
    }

    @Test
    fun `code input is upper-cased, stripped of excluded characters and trimmed to the code length`() =
        runTest(dispatcher) {
            // '0' and 'O' are deliberately outside InviteCodeGenerator.ALPHABET (it omits
            // O/0/I/1/L to stay unambiguous), so this exercises the filter, not just take(6).
            viewModel.onCodeInputChange("4f0O7k2mXX")

            assertEquals("4F7K2M", viewModel.form.value.codeInput)
        }

    @Test
    fun `code input extracts the code from a pasted pairing URI`() = runTest(dispatcher) {
        viewModel.onCodeInputChange("coplanly://pair?code=4F7K2M")

        assertEquals("4F7K2M", viewModel.form.value.codeInput)
    }

    @Test
    fun `code input extracts the code from pasted share text containing a pairing URI`() = runTest(dispatcher) {
        viewModel.onCodeInputChange("Join me on CoPlanly! coplanly://pair?code=4F7K2M Talk soon")

        assertEquals("4F7K2M", viewModel.form.value.codeInput)
    }

    @Test
    fun `redeeming a short code does not hit the repository`() = runTest(dispatcher) {
        viewModel.onCodeInputChange("4F7")
        viewModel.redeemCode()

        coVerify(exactly = 0) { repository.redeem(any()) }
    }

    @Test
    fun `an already-paired failure maps to its own message under the code field`() = runTest(dispatcher) {
        coEvery { repository.redeem("4F7K2M") } returns
            Result.failure(PairingException(PairingError.AlreadyPaired))

        viewModel.onCodeInputChange("4F7K2M")
        viewModel.redeemCode()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.pairing_error_already_paired, viewModel.form.value.codeErrorRes)
    }

    @Test
    fun `every PairingError maps to its own message`() = runTest(dispatcher) {
        val cases = mapOf(
            PairingError.NotFound to R.string.pairing_error_not_found,
            PairingError.Expired to R.string.pairing_error_expired,
            PairingError.NotPending to R.string.pairing_error_not_pending,
            PairingError.SelfPairing to R.string.pairing_error_self_pairing,
            PairingError.AlreadyPaired to R.string.pairing_error_already_paired,
            PairingError.WrongRecipient to R.string.pairing_error_wrong_recipient,
            PairingError.Network to R.string.pairing_error_network,
            PairingError.Unknown("boom") to R.string.pairing_error_unknown
        )

        cases.forEach { (error, expectedRes) ->
            coEvery { repository.redeem("4F7K2M") } returns Result.failure(PairingException(error))

            viewModel.onCodeInputChange("4F7K2M")
            viewModel.redeemCode()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("mapping for $error", expectedRes, viewModel.form.value.codeErrorRes)
        }
    }

    @Test
    fun `a non-pairing exception falls back to the unknown-error message`() = runTest(dispatcher) {
        coEvery { repository.redeem("4F7K2M") } returns Result.failure(RuntimeException("boom"))

        viewModel.onCodeInputChange("4F7K2M")
        viewModel.redeemCode()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.pairing_error_unknown, viewModel.form.value.codeErrorRes)
    }

    @Test
    fun `sending an invitation with an invalid email is rejected before touching the repository`() =
        runTest(dispatcher) {
            every { ValidationUtils.validateEmail("not-an-email") } returns
                ValidationResult.Error("Invalid email format")

            viewModel.onEmailInputChange("not-an-email")
            viewModel.sendEmailInvitation()

            assertEquals(R.string.pairing_error_invalid_email, viewModel.form.value.emailErrorRes)
            coVerify(exactly = 0) { repository.sendEmailInvitation(any()) }
        }

    @Test
    fun `a successful email invitation clears the field and logs once`() = runTest(dispatcher) {
        every { ValidationUtils.validateEmail("other@example.com") } returns ValidationResult.Success
        coEvery { repository.sendEmailInvitation("other@example.com") } returns Result.success(Unit)

        viewModel.onEmailInputChange("other@example.com")
        viewModel.sendEmailInvitation()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.form.value.emailInput)
        coVerify(exactly = 1) { analyticsManager.logInvitationSent() }
    }

    @Test
    fun `a failed email invitation surfaces under the email field and does not log`() = runTest(dispatcher) {
        every { ValidationUtils.validateEmail("other@example.com") } returns ValidationResult.Success
        coEvery { repository.sendEmailInvitation("other@example.com") } returns
            Result.failure(PairingException(PairingError.Network))

        viewModel.onEmailInputChange("other@example.com")
        viewModel.sendEmailInvitation()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.pairing_error_network, viewModel.form.value.emailErrorRes)
        coVerify(exactly = 0) { analyticsManager.logInvitationSent() }
    }

    @Test
    fun `regenerating an invite revokes before creating the replacement`() = runTest(dispatcher) {
        viewModel.regenerateInvite()
        dispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            repository.revokeActiveInvite()
            repository.createOrReuseInviteCode()
        }
    }

    // ---- acceptIncoming / slot re-stamping --------------------------------

    @Test
    fun `accepting an invitation that changes this device's slot re-stamps its records`() =
        runTest(dispatcher) {
            coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
            coEvery { repository.acceptIncoming("invite-1") } returns Result.success("dad")

            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                parentSlotMigrator.reslot(from = "mom", to = "dad", myUid = "user-a")
            }
            coVerify(exactly = 1) { analyticsManager.logInvitationAccepted() }
        }

    @Test
    fun `accepting an invitation that keeps this device's slot does not migrate`() =
        runTest(dispatcher) {
            // The inviter's own device never moves slots; only the accepter's can. Running the
            // migrator here would be harmless (it is a no-op when from == to) but pointless work.
            coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
            coEvery { repository.acceptIncoming("invite-1") } returns Result.success("mom")

            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { parentSlotMigrator.reslot(any(), any(), any()) }
        }

    @Test
    fun `a failed accept never runs the migrator`() = runTest(dispatcher) {
        coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
        coEvery { repository.acceptIncoming("invite-1") } returns
            Result.failure(PairingException(PairingError.NotFound))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { parentSlotMigrator.reslot(any(), any(), any()) }
        assertEquals(R.string.pairing_error_not_found, viewModel.form.value.actionErrorRes)
    }

    @Test
    fun `no local profile yet means nothing to compare, so the migrator is skipped`() =
        runTest(dispatcher) {
            coEvery { userRepository.getCurrentUser() } returns null
            coEvery { repository.acceptIncoming("invite-1") } returns Result.success("dad")

            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { parentSlotMigrator.reslot(any(), any(), any()) }
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

    @Test
    fun `exposes the signed-in account so the screen can name this device`() =
        runTest(dispatcher) {
            // Both parents' phones render the same invite code; the account is the only
            // thing on the screen that distinguishes them.
            viewModel.account.test {
                assertNull(awaitItem())
                assertEquals(ACCOUNT, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun userWithRole(role: String) = User(
        id = "user-a",
        email = "alice@example.com",
        name = "Alice Novak",
        role = role,
        colorCode = "#FF4081"
    )

    private companion object {
        val ACCOUNT = AccountSummary(
            id = "user-a",
            name = "Alice Novak",
            email = "alice@example.com",
            photoUrl = "https://lh3.googleusercontent.com/a/alice"
        )
    }
}
