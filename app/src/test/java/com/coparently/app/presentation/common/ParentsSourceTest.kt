package com.coparently.app.presentation.common

import app.cash.turbine.test
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sharing contract, and the cheap-question path.
 *
 * Both matter for cost rather than correctness, which is exactly why they need tests: nothing
 * about the rendered UI changes if this regresses, only the number of Firestore listeners.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentsSourceTest {

    private val me = User(
        id = "u1",
        email = "olya@example.com",
        name = "Olya",
        role = "mom",
        colorCode = "#FF4081"
    )
    private val partner = PartnerSummary(
        id = "u2",
        name = "Pavel",
        email = "pavel@example.com",
        pairedSinceMillis = 1L,
        role = "dad"
    )

    private fun userRepository(): UserRepository = mockk {
        every { observeCurrentUserId() } returns MutableStateFlow<String?>("u1")
        every { getAllUsers() } returns MutableStateFlow(listOf(me))
        coEvery { getCurrentUserId() } returns "u1"
        coEvery { getUserById("u1") } returns me
    }

    private fun pairingRepository(): PairingRepository = mockk {
        every { observePairingState() } returns MutableStateFlow(PairingState.Paired(partner))
    }

    @Test
    fun `both parents resolve from their own sources`() = runTest {
        val source = ParentsSource(userRepository(), pairingRepository())

        source.observe().test {
            val parents = awaitItem()
            assertEquals(NamedParent("u1", "mom", "Olya"), parents.me)
            assertEquals(NamedParent("u2", "dad", "Pavel"), parents.coParent)
            assertEquals(true, parents.isPaired)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isPaired is true for a legacy pair even though coParent is null`() = runTest {
        // The partner document exists and is Paired, it just predates slot assignment - the
        // case a screen must still recognise "there is a co-parent to choose between" for
        // (AddEditEventScreen's selector), even though coParent itself is null because their
        // slot cannot be resolved.
        val legacyPartner = partner.copy(role = null)
        val pairing: PairingRepository = mockk {
            every { observePairingState() } returns MutableStateFlow(PairingState.Paired(legacyPartner))
        }
        val source = ParentsSource(userRepository(), pairing)

        source.observe().test {
            val parents = awaitItem()
            assertNull(parents.coParent)
            assertEquals(true, parents.isPaired)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isPaired is false when there is no co-parent at all`() = runTest {
        val pairing: PairingRepository = mockk {
            every { observePairingState() } returns MutableStateFlow(PairingState.NotPaired())
        }
        val source = ParentsSource(userRepository(), pairing)

        source.observe().test {
            val parents = awaitItem()
            assertNull(parents.coParent)
            assertEquals(false, parents.isPaired)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `two collectors share one upstream subscription`() = runTest {
        // observePairingState attaches three Firestore snapshot listeners per subscription and
        // re-reads the partner document on every emission, so a per-collector copy is expensive.
        // Five ViewModels expose `parents`; without sharing, opening a second tab doubled it.
        val pairing = pairingRepository()
        val source = ParentsSource(userRepository(), pairing)

        source.observe().test {
            awaitItem()
            source.observe().test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { pairing.observePairingState() }
    }

    @Test
    fun `signedInSlot never touches the pairing side`() = runTest {
        // Asking for your own slot must not stand up a pairing subscription or fetch the
        // co-parent's document. confirmPickup used to collect the whole flow for this.
        val pairing = mockk<PairingRepository>() // no stubs: any call is an error
        val users = userRepository()
        val source = ParentsSource(users, pairing)

        assertEquals("mom", source.signedInSlot())

        verify { pairing wasNot Called }
        coVerify(exactly = 1) { users.getCurrentUserId() }
    }

    @Test
    fun `signedInSlot is null when signed out`() = runTest {
        val users = mockk<UserRepository> {
            every { observeCurrentUserId() } returns flowOf(null)
            every { getAllUsers() } returns flowOf(emptyList())
            coEvery { getCurrentUserId() } returns null
        }
        assertNull(ParentsSource(users, mockk()).signedInSlot())
    }

    @Test
    fun `Parents() the synthetic starting value every stateIn seeds with is not loaded`() {
        // Every ViewModel's `parents` StateFlow starts from this value before the upstream has
        // emitted. It must be indistinguishable from nothing only in what it names, never in
        // whether it is a real answer - otherwise a gate like AddEditEventScreen's selector
        // cannot tell "nobody to choose between" from "we don't know yet".
        assertEquals(false, Parents().loaded)
    }

    @Test
    fun `observe emits loaded = true on its first real emission`() = runTest {
        val source = ParentsSource(userRepository(), pairingRepository())

        source.observe().test {
            val parents = awaitItem()
            assertEquals(true, parents.loaded)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
