package com.coparently.app.data.repository

import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.repository.MessageRepository
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingRepositoryImplTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var authService: FirebaseAuthService
    private lateinit var pairingFunctions: PairingFunctions
    private lateinit var messageRepository: MessageRepository
    private lateinit var repository: PairingRepositoryImpl

    @Before
    fun setUp() {
        firestore = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        pairingFunctions = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "user-a"
        every { firebaseUser.email } returns "a@example.com"
        every { authService.getCurrentUser() } returns firebaseUser

        repository = PairingRepositoryImpl(
            firestore = firestore,
            authService = authService,
            pairingFunctions = pairingFunctions,
            messageRepository = messageRepository
        )
    }

    @Test
    fun `redeem rejects a malformed code without calling the backend`() = runTest {
        val result = repository.redeem("nope")

        assertTrue(result.isFailure)
        assertEquals(
            PairingError.NotFound,
            (result.exceptionOrNull() as PairingException).error
        )
    }

    @Test
    fun `redeem normalizes the code before calling the backend`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.success("user-b")

        val result = repository.redeem("  4f7k2m ")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `redeem surfaces the backend error unchanged`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.failure(PairingException(PairingError.AlreadyPaired))

        val result = repository.redeem("4F7K2M")

        assertEquals(
            PairingError.AlreadyPaired,
            (result.exceptionOrNull() as PairingException).error
        )
    }

    @Test
    fun `unpair delegates to the callable`() = runTest {
        coEvery { pairingFunctions.unpair() } returns Result.success("user-b")

        assertTrue(repository.unpair().isSuccess)
    }
}
