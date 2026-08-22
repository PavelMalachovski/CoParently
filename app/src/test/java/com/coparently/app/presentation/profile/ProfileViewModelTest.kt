package com.coparently.app.presentation.profile

import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProfileViewModel].
 *
 * Two defects are pinned here:
 *
 * 1. [ProfileViewModel.save] must never send a stale [User.partnerId] (or any other field this
 *    screen does not edit) back to the repository. `save` used to send the whole
 *    [ProfileUiState.me] snapshot, which is only as fresh as the time spent on the form — so a
 *    co-parent who unpaired while the screen was open would be silently re-granted access the
 *    next time the user saved an unrelated field.
 * 2. [ProfileViewModel.observeMe] must not leave [ProfileUiState.me] null forever when the Room
 *    row is written asynchronously, after this ViewModel has already started observing it — the
 *    race between sign-in and `UserRepository.ensureProfile()` — and must not spin forever when
 *    the row never arrives at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var meRow: MutableStateFlow<User?>
    private lateinit var userRepository: UserRepository
    private lateinit var pairingRepository: PairingRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        meRow = MutableStateFlow(alice(partnerId = BOB))
        userRepository = mockk(relaxed = true) {
            every { observeCurrentUserId() } returns flowOf(ALICE)
            every { observeUserById(ALICE) } returns meRow
            coEvery { getUserById(ALICE) } answers { meRow.value }
        }
        pairingRepository = mockk(relaxed = true) {
            every { observePairingState() } returns flowOf(PairingState.NotPaired())
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save does not resurrect a partnerId cleared while the screen was open`() = runTest {
        val viewModel = createViewModel()
        runCurrent()
        assertEquals(BOB, viewModel.uiState.value.me?.partnerId)

        // B unpairs while A is still on the screen: the server clears both sides and A's
        // SyncWorker writes partnerId = null into Room. This ViewModel's `me` is a snapshot
        // from before that write and still says BOB.
        meRow.value = meRow.value?.copy(partnerId = null)
        viewModel.updateName("Alice Updated")

        viewModel.save()
        runCurrent()

        val saved = slot<User>()
        coVerify { userRepository.updateUser(capture(saved)) }
        assertNull(
            saved.captured.partnerId,
            "save must not resurrect a partnerId the sync layer already cleared"
        )
        assertEquals("Alice Updated", saved.captured.name)
    }

    @Test
    fun `save only applies the fields this screen owns onto a freshly read row`() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        // Something else changes fields this screen does not own, concurrently with the edit -
        // a role re-stamp and a token refresh are both real background writers.
        meRow.value = meRow.value?.copy(fcmToken = "new-token", role = "dad")
        viewModel.updateAllergies(listOf("peanuts"))

        viewModel.save()
        runCurrent()

        val saved = slot<User>()
        coVerify { userRepository.updateUser(capture(saved)) }
        assertEquals(listOf("peanuts"), saved.captured.allergies)
        // The concurrent external change survives, because save() reads a fresh row rather
        // than sending the draft's stale copy of fields it does not own.
        assertEquals("new-token", saved.captured.fcmToken)
        assertEquals("dad", saved.captured.role)
    }

    @Test
    fun `a row written after the screen opens still arrives instead of leaving me null forever`() =
        runTest {
            // The ensureProfile race: no row exists yet when the screen opens.
            meRow = MutableStateFlow(null)
            every { userRepository.observeUserById(ALICE) } returns meRow
            val viewModel = createViewModel()
            runCurrent()
            assertNull(viewModel.uiState.value.me)
            assertFalse(viewModel.uiState.value.meUnavailable)

            // ensureProfile's asynchronous write lands.
            meRow.value = alice(partnerId = null)
            runCurrent()

            assertEquals(ALICE, viewModel.uiState.value.me?.id)
        }

    @Test
    fun `a row that never appears surfaces as unavailable instead of spinning forever`() = runTest {
        meRow = MutableStateFlow(null)
        every { userRepository.observeUserById(ALICE) } returns meRow
        val viewModel = createViewModel()

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.meUnavailable)
        assertNull(viewModel.uiState.value.me)
    }

    @Test
    fun `retrying re-runs ensureProfile and waits again for the row`() = runTest {
        meRow = MutableStateFlow(null)
        every { userRepository.observeUserById(ALICE) } returns meRow
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.meUnavailable)
        coEvery { userRepository.getCurrentUserId() } returns ALICE
        coEvery { userRepository.ensureProfile() } answers { meRow.value = alice(partnerId = null) }

        viewModel.retryLoadingMe()
        runCurrent()

        coVerify { userRepository.ensureProfile() }
        assertEquals(ALICE, viewModel.uiState.value.me?.id)
        assertFalse(viewModel.uiState.value.meUnavailable)
    }

    private fun createViewModel(): ProfileViewModel =
        ProfileViewModel(userRepository, pairingRepository)

    private fun alice(partnerId: String?) = User(
        id = ALICE,
        email = "alice@example.test",
        name = "Alice",
        role = "mom",
        colorCode = "#FF4081",
        partnerId = partnerId
    )

    private companion object {
        const val ALICE = "alice-uid"
        const val BOB = "bob-uid"
    }
}
