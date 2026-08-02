package com.coparently.app.data.session

import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseUser
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [SessionProfileSynchronizer].
 *
 * The trigger is the point of this class. The logic it drives used to live in the pairing
 * ViewModel, so it only ever ran for a user who opened the pairing screen — and the pairing
 * rewrite dropped it entirely. Hanging it off the auth state instead means it runs for every
 * signed-in user, including one whose session was restored from a build that never wrote a
 * profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionProfileSynchronizerTest {

    private val userRepository: UserRepository = mockk(relaxed = true)
    private val authService: FirebaseAuthService = mockk(relaxed = true)

    @Test
    fun `repairs the profile for a session that is already signed in`() = runTest {
        // AuthStateListener fires immediately on registration, so this is what a cold start
        // with a restored session looks like — no sign-in event required.
        every { authService.getAuthStateFlow() } returns flowOf(user("user-a"))

        synchronizer().observeSessions()

        coVerify(exactly = 1) { userRepository.ensureProfile() }
    }

    @Test
    fun `repairs the profile again when a different account signs in`() = runTest {
        every { authService.getAuthStateFlow() } returns
            flowOf(user("user-a"), null, user("user-b"))

        synchronizer().observeSessions()

        coVerify(exactly = 2) { userRepository.ensureProfile() }
    }

    @Test
    fun `does not repeat the work when the same user is re-emitted`() = runTest {
        // Firebase re-emits the current user on every token refresh.
        every { authService.getAuthStateFlow() } returns
            flowOf(user("user-a"), user("user-a"), user("user-a"))

        synchronizer().observeSessions()

        coVerify(exactly = 1) { userRepository.ensureProfile() }
    }

    @Test
    fun `writes nothing while signed out`() = runTest {
        every { authService.getAuthStateFlow() } returns flowOf(null)

        synchronizer().observeSessions()

        coVerify(exactly = 0) { userRepository.ensureProfile() }
    }

    private fun synchronizer() = SessionProfileSynchronizer(authService, userRepository)

    private fun user(uid: String): FirebaseUser {
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns uid
        return firebaseUser
    }
}
