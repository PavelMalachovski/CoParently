package com.coparently.app.presentation.childinfo

import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.GuestRepository
import com.coparently.app.domain.repository.MedicalPhotoStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull

/**
 * Which child the editor is holding — the one question this ViewModel used to get wrong.
 *
 * `loadChildInfo()` collected the whole `child_info` list for the ViewModel's entire lifetime and
 * set `currentChildInfo` to `list.first()` on **every** emission. So while a parent edited child
 * B, any write touching that table re-emitted the list and reset the state to child A; a
 * background sync tick was enough, and so was an unrelated edit made elsewhere. The prefill was
 * not the damage. The save was: the editor's snapshot-and-`copy()` base had become child A, so
 * the write landed on child A's real row — id, `createdAt` and `createdByFirebaseUid` included —
 * carrying whatever child B's form fields held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChildInfoViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var childInfoRepository: ChildInfoRepository
    private lateinit var children: MutableStateFlow<List<ChildInfo>>

    private val childA = child(id = "a", name = "Anna")
    private val childB = child(id = "b", name = "Bara")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        children = MutableStateFlow(listOf(childA, childB))
        childInfoRepository = mockk(relaxed = true) {
            every { getAllChildInfo() } returns children
            every { observeChildInfoById("a") } returns MutableStateFlow(childA)
            every { observeChildInfoById("b") } returns MutableStateFlow(childB)
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ChildInfoViewModel(
        childInfoRepository,
        mockk<MedicalPhotoStorage>(relaxed = true),
        mockk<GuestRepository>(relaxed = true),
        mockk<FirebaseAuthService>(relaxed = true),
        mockk<AnalyticsManager>(relaxed = true),
        mockk<CrashlyticsManager>(relaxed = true)
    )

    @Test
    fun `an edit of the second child survives the list re-emitting`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.loadChildInfoById("b")
        advanceUntilIdle()
        assertEquals("b", viewModel.currentChildInfo.value?.id)

        // Anything at all that touches the table: a sync tick, an edit to Anna made from another
        // screen, a mirror write. The editor is open on Bara and must stay open on Bara.
        children.value = listOf(childA.copy(childName = "Anna Nováková"), childB)
        advanceUntilIdle()

        assertEquals(
            "b",
            viewModel.currentChildInfo.value?.id,
            "the editor must not be handed a different child by the list"
        )
        assertEquals("Bara", viewModel.currentChildInfo.value?.childName)
    }

    @Test
    fun `loading the list decides nothing about which child is being edited`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(
                ChildInfoUiState.Success(listOf(childA, childB)),
                viewModel.uiState.value,
                "the list screen still gets its list"
            )
            assertNull(
                viewModel.currentChildInfo.value,
                "no child has been opened, so the editor holds none"
            )
        }

    @Test
    fun `a brand-new child starts blank rather than prefilled from the first row`() =
        runTest(dispatcher) {
            // The add flow never calls loadChildInfoById, so whatever this state holds is what
            // the form is prefilled with. It used to hold whoever was first in the list.
            val viewModel = viewModel()
            advanceUntilIdle()

            assertNull(viewModel.currentChildInfo.value)
        }

    @Test
    fun `opening another child replaces the first, rather than racing it`() = runTest(dispatcher) {
        val bara = MutableStateFlow<ChildInfo?>(childB)
        every { childInfoRepository.observeChildInfoById("b") } returns bara
        val viewModel = viewModel()

        viewModel.loadChildInfoById("b")
        advanceUntilIdle()
        viewModel.loadChildInfoById("a")
        advanceUntilIdle()

        // The first observation is cancelled, so its source can no longer write the state.
        bara.value = childB.copy(childName = "Bara from a stale collector")
        advanceUntilIdle()

        assertEquals("a", viewModel.currentChildInfo.value?.id)
        assertEquals("Anna", viewModel.currentChildInfo.value?.childName)
    }

    private fun child(id: String, name: String) = ChildInfo(
        id = id,
        childName = name,
        dateOfBirth = LocalDateTime.of(2018, 5, 4, 0, 0),
        createdAt = LocalDateTime.of(2026, 1, 1, 9, 0),
        updatedAt = LocalDateTime.of(2026, 1, 1, 9, 0)
    )
}
