package com.coparently.app.presentation.pairing

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.data.repository.ParentSlotMigrator
import com.coparently.app.data.session.SignedInAccountSource
import com.coparently.app.domain.custody.CustodyTimestamp
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.custody.SharedCustodyRead
import com.coparently.app.domain.model.AccountSummary
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // No marker unless a test says otherwise: a relaxed mock would answer "" for a
        // String-returning call and silently become a *wrong* before-slot on every accept.
        every { parentSlotMigrator.knownSlot(any()) } returns null
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

    @Test
    fun `the before-slot is the migrator's marker, not Room's role`() = runTest(dispatcher) {
        // Room's `role` is unusable as a "before": the accept path never writes it, it is seeded
        // with a placeholder on profile creation, and it is written non-atomically with respect
        // to the re-stamp - the three reasons `ParentSlotMigrator.reslotIfSlotChanged` already
        // stopped using it. Here the marker says this device's records are stamped "dad"
        // already, while Room's stale row still says "mom". Trusting `role` would re-stamp from
        // a slot no row is in - matching nothing, reporting zero - and then complement a pattern
        // that was already complemented, inverting it.
        every { parentSlotMigrator.knownSlot("user-a") } returns "dad"
        coEvery { userRepository.getCurrentUser() } returns userWithRole("mom")
        coEvery { repository.acceptIncoming("invite-1") } returns Result.success("dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns
            pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns SharedCustodyRead.Absent

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { parentSlotMigrator.reslot(any(), any(), any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
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
    //
    // The complement is persisted through `saveReslotted` (Room only, dates kept) rather than
    // `saveAndActivate` (stamped now, pushed) because it re-expresses an arrangement instead of
    // changing one. Re-stamping it would make this device the unconditional winner of the
    // mirror's staleness comparison, which is how a pattern nobody chose ends up replacing the
    // co-parent's document.

    @Test
    fun `the local pattern is complemented before the two are compared`() = runTest(dispatcher) {
        // Slot 1 with days 0-6, moving to slot 2 - so "my days" become 7-13, which is exactly
        // what the co-parent's slot-1 pattern already says. Same arrangement, no question to ask.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns found(pattern((7..13).toSet(), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 1) {
            custodyModelRepository.saveReslotted(pattern((7..13).toSet(), "mine"))
        }
        // The two agree, so the shared document needs nothing from this device. Writing here
        // would re-date an identical pattern and win every later comparison for it.
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
    }

    @Test
    fun `two disagreeing patterns are put to the user, and nothing is published`() = runTest(dispatcher) {
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns
            found(pattern(setOf(0, 1, 4, 5, 6, 9, 10), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            CustodyConflictPrompt(
                conflict = CustodyConflict.Conflict(
                    // Complemented, not as stored - the whole point.
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
    fun `a conflict still persists the complement before asking`() = runTest(dispatcher) {
        // Losing the prompt - process death, or leaving the pairing screen before the event is
        // collected - must not leave Room holding the un-complemented pattern. After the slot
        // flip that pattern is not merely unreconciled: it hands the accepter's own days to the
        // co-parent, on their own calendar, with nothing to say so.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns
            found(pattern(setOf(0, 1, 4, 5, 6, 9, 10), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            custodyModelRepository.saveReslotted(pattern((7..13).toSet(), "mine"))
        }
    }

    @Test
    fun `the conflict screen is asked for exactly once`() = runTest(dispatcher) {
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns
            found(pattern(setOf(0, 1, 4, 5, 6, 9, 10), "theirs"))

        viewModel.events.test {
            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(PairingEvent.ChooseCustodySchedule, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an absent shared document is the one case that publishes`() = runTest(dispatcher) {
        // The read succeeded and there is no document, so publishing creates the pair's first
        // one rather than replacing anything. Without it, pairing would leave the arrangement
        // stranded on a single phone.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns SharedCustodyRead.Absent

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 1) {
            custodyModelRepository.saveAndActivate(pattern((7..13).toSet(), "mine"))
        }
    }

    @Test
    fun `a shared pattern that could not be read publishes nothing`() = runTest(dispatcher) {
        // "We could not ask" is not "they have no schedule". Publishing on the strength of a
        // failed read replaces a co-parent's pattern that is merely unreadable right now - and
        // it would do so with no screen shown and nobody asked.
        pairedFrom("mom", to = "dad")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns SharedCustodyRead.Unavailable

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
        // The accepter's own calendar is still put right; only the pair's document is left alone.
        coVerify(exactly = 1) {
            custodyModelRepository.saveReslotted(pattern((7..13).toSet(), "mine"))
        }
    }

    @Test
    fun `a pairing that never reaches Room still finishes, and still publishes nothing`() =
        runTest(dispatcher) {
            // The wait for PairingState.Paired times out - a snapshot listener that has not
            // recovered, or another collector winning the mirror's dedupe. The reconciliation
            // must complete rather than hang, and must not read an unanswerable read as an
            // absent document.
            neverPairedLocally(from = "mom", to = "dad")
            coEvery { custodyModelRepository.getActiveModelSync() } returns
                pattern((0..6).toSet(), "mine")
            coEvery { custodyModelRepository.readShared() } returns SharedCustodyRead.Unavailable

            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.form.value.actionErrorRes)
            assertNull(pendingCustodyConflict.prompt.value)
            coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
            coVerify(exactly = 1) {
                custodyModelRepository.saveReslotted(pattern((7..13).toSet(), "mine"))
            }
        }

    @Test
    fun `the accept does not wait for the custody reconciliation`() = runTest(dispatcher) {
        // The pairing has already succeeded server-side; saying so must not sit behind the wait
        // for it to be mirrored into Room, or the button spins for the whole timeout.
        neverPairedLocally(from = "mom", to = "dad")

        viewModel.acceptIncoming("invite-1")
        // Enough to drain the accept itself, but nowhere near the timeout the reconciliation
        // waits out before giving up.
        dispatcher.scheduler.runCurrent()

        assertFalse(viewModel.form.value.isBusy)
        coVerify(exactly = 1) { analyticsManager.logInvitationAccepted() }

        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `the complement is persisted before the wait for the pairing to reach Room`() =
        runTest(dispatcher) {
            // The complement depends on nothing the wait or the shared read provides: `slotChanged`
            // comes from the callable and `mine` is a Room read. Running it after them parks the
            // one write that must not be lost behind a five-second wait and an untimed Firestore
            // `get()`, in a detached coroutine, while the screen already says "Paired" - and
            // popping the Pairing entry, the natural next tap, cancels it.
            neverPairedLocally(from = "mom", to = "dad")
            coEvery { custodyModelRepository.getActiveModelSync() } returns
                pattern((0..6).toSet(), "mine")
            coEvery { custodyModelRepository.readShared() } returns SharedCustodyRead.Absent

            viewModel.acceptIncoming("invite-1")
            // Far short of the wait's timeout, so nothing downstream of it can have run yet.
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) {
                custodyModelRepository.saveReslotted(pattern((7..13).toSet(), "mine"))
            }
            coVerify(exactly = 0) { custodyModelRepository.readShared() }

            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `leaving the pairing screen mid-wait still leaves the complement in Room`() =
        runTest(dispatcher) {
            // Cancelling the ViewModel's scope is what popping the Pairing back-stack entry does.
            // Before the reorder this lost the complement outright, and nothing retried it: the
            // slot marker had already advanced, so the periodic detector sees no change - leaving
            // the accepter's own days assigned to their co-parent, on their own calendar, forever.
            neverPairedLocally(from = "mom", to = "dad")
            coEvery { custodyModelRepository.getActiveModelSync() } returns
                pattern((0..6).toSet(), "mine")

            viewModel.acceptIncoming("invite-1")
            dispatcher.scheduler.runCurrent()
            viewModel.viewModelScope.cancel()
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                custodyModelRepository.saveReslotted(pattern((7..13).toSet(), "mine"))
            }
            coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
        }

    @Test
    fun `an accept that keeps this device's slot does not complement`() = runTest(dispatcher) {
        // Nothing moved, so the stored pattern already means "my days"; complementing it here
        // would invert a schedule nobody asked to change.
        pairedFrom("mom", to = "mom")
        coEvery { custodyModelRepository.getActiveModelSync() } returns pattern((0..6).toSet(), "mine")
        coEvery { custodyModelRepository.readShared() } returns SharedCustodyRead.Absent

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
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
        coEvery { custodyModelRepository.readShared() } returns found(pattern((7..13).toSet(), "theirs"))

        viewModel.acceptIncoming("invite-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(pendingCustodyConflict.prompt.value)
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
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

    /**
     * Stubs a successful accept whose pairing never becomes visible locally, so the wait in
     * `reconcileCustody` runs out. `runTest` advances the timeout on virtual time, so this costs
     * nothing to exercise - and it is the branch on which nothing may be published.
     */
    private fun neverPairedLocally(from: String, to: String) {
        coEvery { userRepository.getCurrentUser() } returns userWithRole(from)
        coEvery { repository.acceptIncoming("invite-1") } returns Result.success(to)
        coEvery { repository.observePairingState() } returns flowOf(PairingState.NotPaired())
        buildViewModel()
    }

    private fun pattern(momDays: Set<Int>, id: String) = CustodyModel(
        id = id,
        modelType = CustodyModelType.CUSTOM,
        patternDays = 14,
        momDayIndices = momDays,
        startDate = java.time.LocalDate.of(2026, 8, 3)
    )

    private fun found(model: CustodyModel) = SharedCustodyRead.Found(
        SharedCustody(
            model = model,
            lastModifiedBy = "user-b",
            lastModifiedAtMillis = CustodyTimestamp.fromWire("2026-08-01T10:00:00"),
            createdAt = "2026-08-01T10:00:00"
        )
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
