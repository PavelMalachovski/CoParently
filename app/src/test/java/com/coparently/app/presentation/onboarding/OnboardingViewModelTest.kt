package com.coparently.app.presentation.onboarding

import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wizard's rules, which are almost entirely about what a parent is allowed to leave out.
 *
 * The questionnaire asks for a great deal — a blood group, hereditary conditions, a phone number
 * for an aunt — and it is collected for the parent's own benefit in an emergency. Data gathered
 * for someone's benefit must not lock them out of their calendar, so the only thing that blocks
 * progress is the one field the app genuinely cannot work without: a name to put on the events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var userRepository: UserRepository
    private lateinit var childInfoRepository: ChildInfoRepository
    private lateinit var viewModel: OnboardingViewModel

    private val storedUser = User(
        id = "u1",
        email = "olya@example.com",
        name = "",
        role = "mom",
        colorCode = "#FF4081",
        partnerId = "bob"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        userRepository = mockk(relaxed = true) {
            coEvery { getCurrentUser() } returns storedUser
            coEvery { getCurrentUserId() } returns "u1"
        }
        childInfoRepository = mockk(relaxed = true) {
            every { getAllChildInfo() } returns flowOf(emptyList())
        }
        viewModel = OnboardingViewModel(userRepository, childInfoRepository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Walks to [step], answering the one required field on the way. */
    private fun walkTo(step: OnboardingStep) {
        viewModel.updateName("Olya")
        while (viewModel.uiState.value.step != step) viewModel.next()
    }

    @Test
    fun `the intro can always be advanced, because it asks for nothing`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertEquals(OnboardingStep.Intro, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.canAdvance)
        assertFalse(viewModel.uiState.value.canSkip)
    }

    @Test
    fun `a blank name is the only thing that blocks progress`() = runTest(dispatcher) {
        viewModel.next()
        advanceUntilIdle()
        assertEquals(OnboardingStep.Profile, viewModel.uiState.value.step)

        viewModel.updateName("   ")
        assertFalse(viewModel.uiState.value.canAdvance)
        viewModel.next()
        assertEquals(OnboardingStep.Profile, viewModel.uiState.value.step)

        viewModel.updateName("Olya")
        assertTrue(viewModel.uiState.value.canAdvance)
    }

    @Test
    fun `nothing medical is ever required`() = runTest(dispatcher) {
        viewModel.next()
        viewModel.updateName("Olya")
        advanceUntilIdle()

        // No date of birth, no phone, no blood type, no allergies - and still advanceable.
        assertTrue(viewModel.uiState.value.canAdvance)
        assertNull(viewModel.uiState.value.dateOfBirth)
    }

    @Test
    fun `every step after the profile can be skipped outright`() = runTest(dispatcher) {
        walkTo(OnboardingStep.Child)
        advanceUntilIdle()

        listOf(
            OnboardingStep.Child,
            OnboardingStep.Relatives,
            OnboardingStep.Custody,
            OnboardingStep.CoParent
        ).forEach { expected ->
            assertEquals(expected, viewModel.uiState.value.step)
            assertTrue(viewModel.uiState.value.canSkip, "$expected must be skippable")
            viewModel.skip()
            advanceUntilIdle()
        }
    }

    @Test
    fun `back never leaves the wizard from its first step`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.back()
        advanceUntilIdle()
        assertEquals(OnboardingStep.Intro, viewModel.uiState.value.step)
    }

    @Test
    fun `the profile write goes onto a freshly read row, so a stale pairing is never resurrected`() =
        runTest(dispatcher) {
            // The account unpairs while the wizard is open: the row this wizard loaded on init
            // said `partnerId = "bob"`, the row it is about to write onto says null.
            coEvery { userRepository.getCurrentUser() } returns storedUser andThen
                storedUser.copy(partnerId = null)

            viewModel.next()
            viewModel.updateName("Olya")
            viewModel.next()
            advanceUntilIdle()

            val saved = slot<User>()
            coVerify { userRepository.updateUser(capture(saved)) }
            assertEquals("Olya", saved.captured.name)
            assertNull(saved.captured.partnerId, "a held snapshot would have put the pairing back")
        }

    @Test
    fun `skipping a step writes nothing, because a skip is not a deletion`() = runTest(dispatcher) {
        coEvery { childInfoRepository.getChildInfoById("c1") } returns null
        every { childInfoRepository.getAllChildInfo() } returns flowOf(
            listOf(
                ChildInfo(
                    id = "c1",
                    childName = "Anya",
                    dateOfBirth = null,
                    allergies = listOf("peanuts"),
                    createdAt = LocalDateTime.parse("2026-01-01T09:00:00"),
                    updatedAt = LocalDateTime.parse("2026-01-01T09:00:00")
                )
            )
        )
        viewModel = OnboardingViewModel(userRepository, childInfoRepository)
        advanceUntilIdle()

        walkTo(OnboardingStep.Child)
        advanceUntilIdle()
        viewModel.skip()
        advanceUntilIdle()

        coVerify(exactly = 0) { childInfoRepository.upsertChildInfo(any()) }
    }

    @Test
    fun `the child and the relatives land on one record, not two`() = runTest(dispatcher) {
        val written = mutableListOf<ChildInfo>()
        coEvery { childInfoRepository.upsertChildInfo(capture(written)) } returns Unit
        coEvery { childInfoRepository.getChildInfoById(any()) } answers {
            written.lastOrNull { it.id == firstArg<String>() }
        }

        walkTo(OnboardingStep.Child)
        viewModel.updateChildName("Anya")
        viewModel.next()
        advanceUntilIdle()

        viewModel.updateRelatives(listOf(EmergencyContact("Baba Vera", "grandmother", "+420 111")))
        viewModel.next()
        advanceUntilIdle()

        assertEquals(2, written.size)
        assertEquals(written[0].id, written[1].id, "the relatives step must edit the same child")
        assertTrue(written[0].emergencyContacts.isEmpty())
        assertEquals("Baba Vera", written[1].emergencyContacts.single().name)
    }

    @Test
    fun `a nameless child is never created, however many relatives are entered`() =
        runTest(dispatcher) {
            walkTo(OnboardingStep.Relatives)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.canEditRelatives)
            viewModel.updateRelatives(listOf(EmergencyContact("Baba Vera", "grandmother", "+420")))
            viewModel.next()
            advanceUntilIdle()

            coVerify(exactly = 0) { childInfoRepository.upsertChildInfo(any()) }
        }

    @Test
    fun `finishing stamps the marker and only then releases the host`() = runTest(dispatcher) {
        walkTo(OnboardingStep.CoParent)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFinished)

        viewModel.finish()
        advanceUntilIdle()

        val saved = mutableListOf<User>()
        coVerify { userRepository.updateUser(capture(saved)) }
        assertTrue(
            saved.last().onboardingCompletedAt?.isNotBlank() == true,
            "the marker must be stamped, or the wizard reappears on the next launch"
        )
        assertTrue(viewModel.uiState.value.isFinished)
    }

    @Test
    fun `an account that already has answers does not have to retype them`() = runTest(dispatcher) {
        coEvery { userRepository.getCurrentUser() } returns storedUser.copy(
            name = "Olya",
            phone = "+420 777"
        )
        every { childInfoRepository.getAllChildInfo() } returns flowOf(
            listOf(
                ChildInfo(
                    id = "c1",
                    childName = "Anya",
                    dateOfBirth = LocalDateTime.parse("2018-05-04T00:00:00"),
                    allergies = listOf("peanuts"),
                    createdAt = LocalDateTime.parse("2026-01-01T09:00:00"),
                    updatedAt = LocalDateTime.parse("2026-01-01T09:00:00")
                )
            )
        )
        viewModel = OnboardingViewModel(userRepository, childInfoRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Olya", state.name)
        assertEquals("+420 777", state.phone)
        assertEquals("Anya", state.childName)
        assertEquals(listOf("peanuts"), state.childAllergies)
        assertEquals(2018, state.childDateOfBirth?.year)
    }
}
