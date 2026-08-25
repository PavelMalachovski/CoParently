package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.PetDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestorePetDataSource
import com.coparently.app.domain.model.Pet
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

/**
 * The pet delete path (CQ-19).
 *
 * A pet was deleted by removing the Firestore document and discarding the `Result`, which is
 * what `data/sync/Tombstone.kt` exists to forbid. Two failures came out of that, and this class
 * pins both fixes: a *successful* removal left the co-parent's phone nothing to learn from — a
 * vanished document is not a fact that can be delivered, and nothing reconciles by absence — so
 * they kept the pet for ever; and a *refused* one removed the local row anyway, leaving the
 * document alive for the next download to put back.
 *
 * The pet repository had no tests at all before this (CQ-13). These are the delete path only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetRepositoryImplTest {

    private lateinit var petDao: PetDao
    private lateinit var userDao: UserDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestorePetDataSource: FirestorePetDataSource
    private lateinit var repository: PetRepositoryImpl

    @Before
    fun setup() {
        petDao = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        firebaseAuthService = mockk(relaxed = true)
        firestorePetDataSource = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns ALICE
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser

        repository = PetRepositoryImpl(
            petDao,
            userDao,
            firebaseAuthService,
            firestorePetDataSource
        )
    }

    @Test
    fun `deleting a pet tombstones the document instead of removing it`() = runTest {
        coEvery {
            firestorePetDataSource.tombstonePet(any(), any(), any())
        } returns Result.success(Unit)

        repository.deletePet(pet())

        coVerify { petDao.markDeleted(PET_ID, any()) }
        coVerify { firestorePetDataSource.tombstonePet(PET_ID, any(), ALICE) }
        // Delivered, so the outbox entry has done its job.
        coVerify { petDao.deletePetById(PET_ID) }
    }

    @Test
    fun `a refused tombstone leaves the row queued rather than gone`() = runTest {
        coEvery {
            firestorePetDataSource.tombstonePet(any(), any(), any())
        } returns Result.failure(IOException("offline"))

        repository.deletePet(pet())

        coVerify { petDao.markDeleted(PET_ID, any()) }
        coVerify(exactly = 0) { petDao.deletePetById(PET_ID) }
    }

    @Test
    fun `deleting while signed out queues the deletion for the next sync`() = runTest {
        every { firebaseAuthService.getCurrentUser() } returns null

        repository.deletePet(pet())

        coVerify { petDao.markDeleted(PET_ID, any()) }
        coVerify(exactly = 0) { firestorePetDataSource.tombstonePet(any(), any(), any()) }
        coVerify(exactly = 0) { petDao.deletePetById(any()) }
    }

    private fun pet() = Pet(
        id = PET_ID,
        name = "Rex",
        createdAt = NOW,
        updatedAt = NOW,
        createdByFirebaseUid = ALICE,
        lastModifiedBy = ALICE
    )

    private companion object {
        const val ALICE = "alice-uid"
        const val PET_ID = "pet-1"
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)
    }
}
