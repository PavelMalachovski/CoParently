package com.coparently.app.presentation.onboarding

import com.coparently.app.data.repository.FamilySettingsRepository
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.Pet
import com.coparently.app.domain.model.PetSpecies
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.PetRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    private lateinit var petRepository: PetRepository
    private lateinit var familySettingsRepository: FamilySettingsRepository
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
        petRepository = mockk(relaxed = true) {
            every { getAllPets() } returns flowOf(emptyList())
        }
        familySettingsRepository = mockk(relaxed = true) {
            every { agreedRatioOrDefault() } returns SplitRatio.EVEN
        }
        viewModel = OnboardingViewModel(
            userRepository,
            childInfoRepository,
            petRepository,
            familySettingsRepository
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Walks to [step], answering the one required field on the way. */
    private fun walkTo(step: OnboardingStep) {
        viewModel.updateName("Olya")
        while (viewModel.uiState.value.step != step) viewModel.next()
    }

    /** The draft the child step opens with — the wizard always has one to fill in. */
    private fun firstChildId(): String = viewModel.uiState.value.children.first().id

    /** The draft the pet step opens with. */
    private fun firstPetId(): String = viewModel.uiState.value.pets.first().id

    @Test
    fun `the intro can always be advanced, because it asks for nothing`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertEquals(OnboardingStep.Intro, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.canAdvance)
        assertFalse(viewModel.uiState.value.canSkip)
    }

    @Test
    fun `a blank name is the only thing that blocks progress`() = runTest(dispatcher) {
        // Intro, then the family question, then the profile.
        viewModel.next()
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
            OnboardingStep.Split,
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
    fun `skipping the last step finishes, rather than doing nothing`() = runTest(dispatcher) {
        // Leaving the co-parent invitation unanswered is a supported outcome: it lands the
        // parent unpaired on Home, where the app's own "connect your co-parent" prompt lives.
        // Advancing blindly instead left Skip on this step as a button that did nothing.
        walkTo(OnboardingStep.CoParent)
        advanceUntilIdle()

        viewModel.skip()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
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
            // Let the wizard's own prefill read land first, against the row as it then was.
            advanceUntilIdle()

            // The account unpairs while the wizard is open: the row this wizard loaded on init
            // said `partnerId = "bob"`, the row it is about to write onto says null.
            //
            // Staged by *when* the stored row changes, not by an `andThen` sequence: the latter
            // silently depends on the wizard making exactly one read before this one, so a
            // second read added anywhere in `init` would hand the save the wrong element of the
            // sequence and fail here, for a reason that has nothing to do with what this test
            // is about.
            coEvery { userRepository.getCurrentUser() } returns storedUser.copy(partnerId = null)

            // Intro, then Family, then Profile: the name is entered on the step that asks for
            // it, and the two Nexts before it are the two steps that now precede it.
            viewModel.next()
            viewModel.next()
            viewModel.updateName("Olya")
            viewModel.next()
            advanceUntilIdle()

            // A list, not a `slot`: the Family step writes first and MockK refuses a slot
            // capture across more than one matched call rather than silently keeping the last.
            val saved = mutableListOf<User>()
            coVerify { userRepository.updateUser(capture(saved)) }
            val profileWrite = saved.last()
            assertEquals("Olya", profileWrite.name)
            assertNull(profileWrite.partnerId, "a held snapshot would have put the pairing back")
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
        viewModel = OnboardingViewModel(
            userRepository,
            childInfoRepository,
            petRepository,
            familySettingsRepository
        )
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
        val anya = firstChildId()
        viewModel.updateChildName(anya, "Anya")
        viewModel.next()
        advanceUntilIdle()

        viewModel.updateRelatives(
            anya,
            listOf(EmergencyContact("Baba Vera", "grandmother", "+420 111"))
        )
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
            viewModel.updateRelatives(
                firstChildId(),
                listOf(EmergencyContact("Baba Vera", "grandmother", "+420"))
            )
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
    fun `a step's save and the completion marker cannot overwrite each other`() =
        runTest(dispatcher) {
            // Both follow the same read-modify-write shape against the same row. Interleaved,
            // whichever wrote second would silently drop the other's field - and losing the
            // marker means the wizard reappears on the next launch over data already entered.
            var stored = storedUser
            coEvery { userRepository.getCurrentUser() } answers { stored }
            coEvery { userRepository.updateUser(any()) } answers { stored = firstArg() }

            viewModel.next()
            viewModel.next()
            viewModel.updateName("Olya")
            viewModel.next()
            viewModel.finish()
            advanceUntilIdle()

            assertEquals("Olya", stored.name)
            assertTrue(stored.onboardingCompletedAt?.isNotBlank() == true)
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
        viewModel = OnboardingViewModel(
            userRepository,
            childInfoRepository,
            petRepository,
            familySettingsRepository
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Olya", state.name)
        assertEquals("+420 777", state.phone)
        val anya = state.children.single()
        assertEquals("Anya", anya.name)
        assertEquals(listOf("peanuts"), anya.allergies)
        assertEquals(2018, anya.dateOfBirth?.year)
        assertEquals("c1", anya.id, "the stored record is edited, not duplicated")
    }

    @Test
    fun `two children are written as two records, not one`() = runTest(dispatcher) {
        val written = mutableListOf<ChildInfo>()
        coEvery { childInfoRepository.upsertChildInfo(capture(written)) } returns Unit
        coEvery { childInfoRepository.getChildInfoById(any()) } answers {
            written.lastOrNull { it.id == firstArg<String>() }
        }

        walkTo(OnboardingStep.Child)
        viewModel.updateChildName(firstChildId(), "Anya")
        viewModel.addChild()
        viewModel.updateChildName(viewModel.uiState.value.children[1].id, "Petr")
        viewModel.next()
        advanceUntilIdle()

        assertEquals(listOf("Anya", "Petr"), written.map { it.childName })
        assertEquals(2, written.map { it.id }.toSet().size, "each child needs its own record")
    }

    @Test
    fun `the relatives land on the child they were entered for, not the first`() =
        runTest(dispatcher) {
            val written = mutableListOf<ChildInfo>()
            coEvery { childInfoRepository.upsertChildInfo(capture(written)) } returns Unit
            coEvery { childInfoRepository.getChildInfoById(any()) } answers {
                written.lastOrNull { it.id == firstArg<String>() }
            }

            walkTo(OnboardingStep.Child)
            viewModel.updateChildName(firstChildId(), "Anya")
            viewModel.addChild()
            val petr = viewModel.uiState.value.children[1].id
            viewModel.updateChildName(petr, "Petr")
            viewModel.next()
            advanceUntilIdle()
            written.clear()

            // The step defaults to the first named child; picking the second is what the chip
            // row does, and is the whole point of the picker existing.
            viewModel.selectRelativesChild(petr)
            viewModel.updateRelatives(petr, listOf(EmergencyContact("Baba Vera", "grandmother", "+420")))
            viewModel.next()
            advanceUntilIdle()

            val anyaWrite = written.first { it.childName == "Anya" }
            val petrWrite = written.first { it.childName == "Petr" }
            assertTrue(anyaWrite.emergencyContacts.isEmpty(), "a flat list used to file these here")
            assertEquals("Baba Vera", petrWrite.emergencyContacts.single().name)
        }

    @Test
    fun `the relatives step follows a child whose name is cleared`() = runTest(dispatcher) {
        walkTo(OnboardingStep.Child)
        val anya = firstChildId()
        viewModel.updateChildName(anya, "Anya")
        viewModel.addChild()
        val petr = viewModel.uiState.value.children[1].id
        viewModel.updateChildName(petr, "Petr")
        viewModel.selectRelativesChild(petr)

        viewModel.updateChildName(petr, "")

        // Not left pointing at a draft that no longer takes contacts.
        assertEquals(anya, viewModel.uiState.value.relativesChild?.id)
    }

    @Test
    fun `removing a draft nobody named never reaches Room`() = runTest(dispatcher) {
        walkTo(OnboardingStep.Child)
        viewModel.addChild()
        val blank = viewModel.uiState.value.children[1].id

        viewModel.removeChild(blank)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.children.size)
        coVerify(exactly = 0) { childInfoRepository.deleteChildInfo(any()) }
    }

    @Test
    fun `removing a child the wizard already wrote deletes its record`() = runTest(dispatcher) {
        val stored = ChildInfo(
            id = "c1",
            childName = "Anya",
            dateOfBirth = null,
            createdAt = LocalDateTime.parse("2026-01-01T09:00:00"),
            updatedAt = LocalDateTime.parse("2026-01-01T09:00:00")
        )
        every { childInfoRepository.getAllChildInfo() } returns flowOf(listOf(stored))
        coEvery { childInfoRepository.getChildInfoById("c1") } returns stored
        viewModel = OnboardingViewModel(
            userRepository,
            childInfoRepository,
            petRepository,
            familySettingsRepository
        )
        advanceUntilIdle()

        // Dropping the draft alone would leave a child the parent explicitly removed sitting in
        // the child list, because the step saves on Next.
        viewModel.removeChild("c1")
        advanceUntilIdle()

        coVerify(exactly = 1) { childInfoRepository.deleteChildInfo(stored) }
    }

    @Test
    fun `a removed last draft is replaced, so the step always has a form`() = runTest(dispatcher) {
        walkTo(OnboardingStep.Child)
        viewModel.removeChild(firstChildId())
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.children.size)
        assertTrue(viewModel.uiState.value.children.single().isBlank)
    }

    @Test
    fun `two pets are written as two records`() = runTest(dispatcher) {
        val written = mutableListOf<Pet>()
        coEvery { petRepository.upsertPet(capture(written)) } returns Unit
        coEvery { petRepository.getPetById(any()) } answers {
            written.lastOrNull { it.id == firstArg<String>() }
        }
        viewModel.setCaresFor(setOf(FamilyKind.PETS))

        walkTo(OnboardingStep.Pet)
        viewModel.setPetName(firstPetId(), "Rex")
        viewModel.setPetSpecies(firstPetId(), PetSpecies.DOG)
        viewModel.addPet()
        val second = viewModel.uiState.value.pets[1].id
        viewModel.setPetName(second, "Murka")
        viewModel.setPetSpecies(second, PetSpecies.CAT)
        viewModel.next()
        advanceUntilIdle()

        assertEquals(listOf("Rex", "Murka"), written.map { it.name })
        assertEquals(listOf(PetSpecies.DOG, PetSpecies.CAT), written.map { it.species })
    }

    @Test
    fun `an account with two stored children opens with both`() = runTest(dispatcher) {
        every { childInfoRepository.getAllChildInfo() } returns flowOf(
            listOf(
                ChildInfo(
                    id = "c1",
                    childName = "Anya",
                    dateOfBirth = null,
                    createdAt = LocalDateTime.parse("2026-01-01T09:00:00"),
                    updatedAt = LocalDateTime.parse("2026-01-01T09:00:00")
                ),
                ChildInfo(
                    id = "c2",
                    childName = "Petr",
                    dateOfBirth = null,
                    createdAt = LocalDateTime.parse("2026-01-01T09:00:00"),
                    updatedAt = LocalDateTime.parse("2026-01-01T09:00:00")
                )
            )
        )
        viewModel = OnboardingViewModel(
            userRepository,
            childInfoRepository,
            petRepository,
            familySettingsRepository
        )
        advanceUntilIdle()

        assertEquals(listOf("Anya", "Petr"), viewModel.uiState.value.children.map { it.name })
    }
}
