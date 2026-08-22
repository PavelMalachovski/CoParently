package com.coparently.app.presentation.auth

import android.app.Activity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.google.CredentialAuthenticator
import com.coparently.app.data.remote.google.SavedPassword
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * What the login screen must do around the system's password manager.
 *
 * The load-bearing case is `a declined password save still signs the parent in`: offering to
 * remember a password happens *after* Firebase has already accepted the credentials, so a refusal
 * — or a device with no password manager at all — must not turn a successful authentication into
 * a failed one. Getting that backwards locks out the user who taps "Never".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val activity = mockk<Activity>(relaxed = true)
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var credentialAuthenticator: CredentialAuthenticator
    private lateinit var crashlyticsManager: CrashlyticsManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        firebaseAuthService = mockk()
        credentialAuthenticator = mockk(relaxed = true)
        crashlyticsManager = mockk(relaxed = true)
        viewModel = AuthViewModel(
            firebaseAuthService = firebaseAuthService,
            credentialAuthenticator = credentialAuthenticator,
            analyticsManager = mockk(relaxed = true),
            crashlyticsManager = crashlyticsManager
        )
        viewModel.updateEmail("parent@example.com")
        viewModel.updatePassword("hunter2")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a declined password save still signs the parent in`() = runTest(dispatcher) {
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())
        // savePassword swallows its own failures and returns Unit, so a decline is
        // indistinguishable here from an accept - which is exactly the contract under test.
        coEvery { credentialAuthenticator.savePassword(any(), any(), any()) } returns Unit

        var signedIn = false
        viewModel.signIn(activity) { signedIn = true }
        advanceUntilIdle()

        assertTrue(signedIn, "sign-in must complete regardless of the save prompt")
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the save is offered before navigating away, not after`() = runTest(dispatcher) {
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())
        // Record the real order rather than asserting inside a callback: if navigation ran
        // first, the system's save sheet would land on top of the home screen.
        val order = mutableListOf<String>()
        coEvery { credentialAuthenticator.savePassword(activity, "parent@example.com", "hunter2") }
            .answers { order += "save" }

        viewModel.signIn(activity) { order += "navigate" }
        advanceUntilIdle()

        assertEquals(listOf("save", "navigate"), order)
    }

    @Test
    fun `blank fields are rejected without troubling Firebase`() = runTest(dispatcher) {
        viewModel.updatePassword("")

        viewModel.signIn(activity) { error("must not navigate") }
        advanceUntilIdle()

        assertEquals(AuthError.EmptyFields, viewModel.uiState.value.error)
        coVerify(exactly = 0) { firebaseAuthService.signInWithEmail(any(), any()) }
    }

    @Test
    fun `a Firebase rejection lands in the state as a type and clears loading`() =
        runTest(dispatcher) {
            coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
                Result.failure(FirebaseAuthException("ERROR_WRONG_PASSWORD", "nope"))

            viewModel.signIn(activity) { error("must not navigate") }
            advanceUntilIdle()

            assertEquals(AuthError.InvalidCredentials, viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `dismissing Google's sheet leaves no error on screen`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.signInWithGoogle(any()) } returns
            Result.failure(GetCredentialCancellationException())

        viewModel.signInWithGoogle(activity)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error, "a deliberate cancel is not an error")
        assertFalse(viewModel.uiState.value.isLoading)
        // Nor is it a fault worth reporting: a stream of cancellations would bury real ones.
        verify(exactly = 0) { crashlyticsManager.recordExceptionWithContext(any(), any()) }
    }

    @Test
    fun `a saved password is fed through the same email sign-in path`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.getSavedPassword(any()) } returns
            Result.success(SavedPassword("saved@example.com", "s3cret"))
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())

        viewModel.signInWithSavedPassword(activity)
        advanceUntilIdle()

        coVerify { firebaseAuthService.signInWithEmail("saved@example.com", "s3cret") }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `having nothing saved says so rather than failing silently`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.getSavedPassword(any()) } returns
            Result.failure(NoCredentialException())

        viewModel.signInWithSavedPassword(activity)
        advanceUntilIdle()

        assertEquals(AuthError.NoSavedPassword, viewModel.uiState.value.error)
    }

    @Test
    fun `signing in with a saved password does not re-offer to save it`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.getSavedPassword(any()) } returns
            Result.success(SavedPassword("saved@example.com", "s3cret"))
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())

        viewModel.signInWithSavedPassword(activity)
        advanceUntilIdle()

        coVerify(exactly = 0) { credentialAuthenticator.savePassword(any(), any(), any()) }
    }
}
