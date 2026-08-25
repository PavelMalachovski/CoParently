package com.coparently.app.presentation.common

import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * A real [ParentsSource] over stubbed repositories, for ViewModel tests that only need the
 * dependency to exist.
 *
 * Deliberately the real class rather than a mock of it: the derivation it performs — matching
 * the signed-in row *by uid*, and dropping a co-parent whose slot is unknown — is the part that
 * must not silently change, so a test that stubs it away would keep passing if it did.
 *
 * @param me The signed-in user's stored row, or null when the profile has not loaded.
 * @param partner The paired co-parent, or null when unpaired.
 */
fun testParentsSource(me: User? = null, partner: PartnerSummary? = null): ParentsSource {
    val userRepository = mockk<UserRepository> {
        every { observeCurrentUserId() } returns flowOf(me?.id)
        every { getAllUsers() } returns flowOf(listOfNotNull(me))
        // The cheap path ParentsSource.signedInSlot() takes; it must not need the pairing side.
        coEvery { getCurrentUserId() } returns me?.id
        // The signed-in row must carry the pairing, or the two halves of this double describe
        // different accounts: `coParentUid()` reads `partnerId` off this row while `partner`
        // below is wired only into the pairing side, so a fake without it reports every paired
        // account as unpaired — and a test would conclude the save path is fine when it is not.
        coEvery { getUserById(any()) } returns
            me?.let { row -> partner?.let { row.copy(partnerId = it.id) } ?: row }
    }
    val pairingRepository = mockk<PairingRepository> {
        every { observePairingState() } returns flowOf(
            partner?.let { PairingState.Paired(it) } ?: PairingState.NotPaired()
        )
    }
    return ParentsSource(userRepository, pairingRepository)
}
