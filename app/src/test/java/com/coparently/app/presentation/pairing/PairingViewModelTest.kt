package com.coparently.app.presentation.pairing

import app.cash.turbine.test
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.data.repository.ParentSlotMigrator
import com.coparently.app.data.session.SignedInAccountSource
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.AccountSummary
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
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
    private lateinit var custodyModelRepository: CustodyModelRepository
    private lateinit var pendingCustodyConflict: PendingCustodyConflict
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
        custodyModelRepository = mockk(relaxed = true)
        pendingCustodyConflict = PendingCustodyConflict()
        coEvery { repository.observePairingState() } returns
            flowOf(PairingState.NotPaired())
        signedInAccountSource = mockk(relaxed = true)
        every { signedInAccountSource.observe() } returns flowOf(ACCOUNT)
        buildViewModel()
    }

    /**
     * Builds the subject from whatever the current stubs say.
     *
     * The custody tests restub `observePairingState()` first: `reconcileCustody` waits for the
     * pairing to be mirrored into Room before reading the shared document, and it observes that
     * through the same flow the screen does. A ViewModel built against the default `NotPaired`
     * stub would take the timeout branch instead — correct behaviour, but not the branch those
     * tests are about.
     */
    private fun buildViewModel() {
        viewModel = PairingViewModel(
            pairingRepository = repository,
            qrCodeService = mockk<QRCodeService>(relaxed = true),
            analyticsManager = analyticsManager,
            userRepository = userRepository,
            parentSlotMigrator = parentSlotMigrator,
            custodyModelRepository = custodyModelRepository,
            pendingCustodyConflict = pendingCustodyConflict,
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
    fun `accepting when the server reports no role does not migrate but still succeeds`() =
        runTest(dispatcher) {
            // A staged rollout can pair a client against a backend build that predates the
            // role field (or the reverse). Pairing already succeeded server-side; only the
            // migration hint is missing, so this must behave like an ordinary successful
            // accept — not a failure.
            coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
            coEvery { repository.acceptIncoming("invite-1") } returns Result.success(null)

            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { parentSlotMigrator.reslot(any(), any(), any()) }
            coVerify(exactly = 1) { analyticsManager.logInvitationAccepted() }
            assertEquals(null, viewModel.form.value.actionErrorRes)
        }

    @Test
    fun `a migrator failure is swallowed, not reported as a failed pairing`() = runTest(dispatcher) {
        // The pairing itself already committed server-side and the invitation is no longer
        // pending by the time the migrator could throw (the blank-uid guard, or a
        // SQLiteException) — there is no way to retry the accept. Crashing here, or reporting
        // this as a failed accept, would be strictly worse than the re-stamp silently not
        // happening.
        coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
        coEvery { repository.acceptIncoming("invite-1") } returns Result.success("dad")
        coEvery { parentSlotMigrator.reslot(any(), any(), any()) } throws IllegalStateException("boom")

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { analyticsManager.logInvitationAccepted() }
        assertEquals(null, viewModel.form.value.actionErrorRes)
    }

    // ---- redeemCode / slot re-stamping -------------------------------------
    //
    // redeemCode() and acceptIncoming() are two different entry points to the same
    // acceptPairingInvitation callable (QR/code/deep-link redemption vs. accepting an
    // addressed invitation), and either one can flip this device's parent slot — these tests
    // pin that redeemCode() reaches the migrator by the same route as acceptIncoming(),
    // not a second, divergent copy of the comparison.

    @Test
    fun `redeeming a code that changes this device's slot re-stamps its records`() =
        runTest(dispatcher) {
            coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
            coEvery { repository.redeem("4F7K2M") } returns Result.success("dad")

            viewModel.onCodeInputChange("4F7K2M")
            viewModel.redeemCode()
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                parentSlotMigrator.reslot(from = "mom", to = "dad", myUid = "user-a")
            }
        }

    @Test
    fun `redeeming a code that keeps this device's slot does not migrate`() =
        runTest(dispatcher) {
            coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
            coEvery { repository.redeem("4F7K2M") } returns Result.success("mom")

            viewModel.onCodeInputChange("4F7K2M")
            viewModel.redeemCode()
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { parentSlotMigrator.reslot(any(), any(), any()) }
        }

    @Test
    fun `a failed redeem never runs the migrator`() = runTest(dispatcher) {
        coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
        coEvery { repository.redeem("4F7K2M") } returns
            Result.failure(PairingException(PairingError.NotFound))

        viewModel.onCodeInputChange("4F7K2M")
        viewModel.redeemCode()
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

    // ---- custody reconciliation --------------------------------------------
    //
    // The order these pin is the sharpest thing on this branch: the slot flips, then the local
    // pattern is complemented for the new slot, and only then are the two compared. Complement
    // after comparing and the screen offers a parent their own schedule inverted.

    @Test
    fun `the local pattern is complemented before the two are compared`() = runTest(dispatcher) {
        // Slot 1 with days 0-6, moving to slot 2 — so "my days" become 7-13, which is exactly
        // what the co-parent's slot-1 pattern already says. Same arrangement, no question to ask.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.getShared() } returns shared(pattern((7..13).toSet(), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 1) {
            custodyModelRepository.saveAndActivate(pattern((7..13).toSet(), "theirs"))
        }
    }

    @Test
    fun `two disagreeing patterns are put to the user, and nothing is written`() = runTest(dispatcher) {
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.getShared() } returns
            shared(pattern(setOf(0, 1, 4, 5, 6, 9, 10), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            CustodyConflictPrompt(
                conflict = CustodyConflict.Conflict(
                    // Complemented, not as stored — the whole point.
                    mine = pattern((7..13).toSet(), "mine"),
                    theirs = pattern(setOf(0, 1, 4, 5, 6, 9, 10), "theirs")
                ),
                // The slot the callable reported, not the stale one Room still holds.
                mySlot = "dad"
            ),
            pendingCustodyConflict.prompt.value
        )
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
    }

    @Test
    fun `the conflict screen is asked for exactly once`() = runTest(dispatcher) {
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.getShared() } returns
            shared(pattern(setOf(0, 1, 4, 5, 6, 9, 10), "theirs"))

        viewModel.events.test {
            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(PairingEvent.ChooseCustodySchedule, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no shared pattern keeps the local one, complemented for the new slot`() = runTest(dispatcher) {
        // getShared() answers null both for "no document" and for a read it could not make.
        // Either way the local pattern must end up describing this device's *new* slot.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.getShared() } returns null

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 1) {
            custodyModelRepository.saveAndActivate(pattern((7..13).toSet(), "mine"))
        }
    }

    @Test
    fun `an accept that keeps this device's slot does not complement`() = runTest(dispatcher) {
        // Nothing moved, so the stored pattern already means "my days"; complementing it here
        // would invert a schedule nobody asked to change.
        pairedFrom("mom", to = "mom")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.getShared() } returns null

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            custodyModelRepository.saveAndActivate(pattern((0..6).toSet(), "mine"))
        }
    }

    @Test
    fun `an accept that reports no slot leaves custody untouched`() = runTest(dispatcher) {
        // With no reported slot there is no way to know whether the pattern needs complementing,
        // and comparing an un-complemented pattern is worse than not comparing at all.
        pairedFrom("mom", to = null)
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.getShared() } returns shared(pattern((7..13).toSet(), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
    }

    @Test
    fun `a failed custody reconciliation is not reported as a failed pairing`() = runTest(dispatcher) {
        // The pairing has already committed server-side and the invitation is no longer pending,
        // so there is nothing to retry — a Room failure here must not surface as a failed accept.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } throws IllegalStateException("boom")

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.form.value.actionErrorRes)
        coVerify(exactly = 1) { analyticsManager.logInvitationAccepted() }
    }

    /**
     * Stubs a successful accept that moves this device from [from] to [to], with the pairing
     * already visible as [PairingState.Paired] — the state `reconcileCustody` waits for before
     * reading the shared document.
     */
    private fun pairedFrom(from: String, to: String?) {
        coEvery { userRepository.getCurrentUser() } returns userWithRole(from)
        coEvery { repository.acceptIncoming("invite-1") } returns Result.success(to)
        coEvery { repository.observePairingState() } returns flowOf(
            PairingState.Paired(
                partner = PartnerSummary(
                    id = "user-b",
                    name = "Bob Novak",
                    email = "bob@example.com",
                    pairedSinceMillis = null
                )
            )
        )
        buildViewModel()
    }

    private fun pattern(momDays: Set<Int>, id: String) = CustodyModel(
        id = id,
        modelType = CustodyModelType.CUSTOM,
        patternDays = 14,
        momDayIndices = momDays,
        startDate = java.time.LocalDate.of(2026, 8, 3)
    )

    private fun shared(model: CustodyModel) = SharedCustody(
        model = model,
        lastModifiedBy = "user-b",
        lastModifiedAt = "2026-08-01T10:00:00",
        createdAt = "2026-08-01T10:00:00"
    )

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
