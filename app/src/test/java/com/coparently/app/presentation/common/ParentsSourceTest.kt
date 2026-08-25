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
            // `colorCode` rides along now that a parent picks their own colour: the signed-in
            // parent's comes off their Room row, the co-parent's off their profile document —
            // null here, because that fixture predates anyone choosing.
            assertEquals(NamedParent("u1", "mom", "Olya", colorCode = "#FF4081"), parents.me)
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

    @Test
    fun `coParentUid answers without the pairing side, so a save path need not subscribe`() =
        runTest {
            // The reason this exists: `parents` is shared with `WhileSubscribed`, so a route
            // that never collects it reads `Parents()` forever — and a save path that asked it
            // who the co-parent is wrote every shared expense as a claim on nobody.
            val pairing = mockk<PairingRepository>() // no stubs: any call is an error
            val users = mockk<UserRepository> {
                coEvery { getCurrentUserId() } returns "u1"
                coEvery { getUserById("u1") } returns me.copy(partnerId = "u2")
            }

            assertEquals("u2", ParentsSource(users, pairing).coParentUid())

            verify { pairing wasNot Called }
        }

    @Test
    fun `coParentUid is null for an unpaired account, and for one with no local row`() = runTest {
        val unpaired = mockk<UserRepository> {
            coEvery { getCurrentUserId() } returns "u1"
            coEvery { getUserById("u1") } returns me
        }
        assertNull(ParentsSource(unpaired, mockk()).coParentUid())

        // The window `UserRepositoryImpl` leaves open on a fresh install: signed in, no row yet.
        val rowless = mockk<UserRepository> {
            coEvery { getCurrentUserId() } returns "u1"
            coEvery { getUserById("u1") } returns null
        }
        assertNull(ParentsSource(rowless, mockk()).coParentUid())
    }

    @Test
    fun `coParentUid refuses a row that names itself as its own partner`() = runTest {
        // Not hypothetical: a partnerId equal to the signed-in uid would put the payer twice
        // into `splitBetween` and charge them their own share on top of the whole amount.
        val selfPaired = mockk<UserRepository> {
            coEvery { getCurrentUserId() } returns "u1"
            coEvery { getUserById("u1") } returns me.copy(partnerId = "u1")
        }
        assertNull(ParentsSource(selfPaired, mockk()).coParentUid())
    }
}
