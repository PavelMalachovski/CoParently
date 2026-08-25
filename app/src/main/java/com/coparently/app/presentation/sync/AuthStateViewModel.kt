package com.coparently.app.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.onboarding.OnboardingState
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.PetRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * ViewModel for managing authentication state across the app.
 * Provides authentication state to determine navigation routing.
 *
 * It also answers the second half of that routing question — whether this account still has to
 * be walked through the first-run questionnaire. Both answers live here rather than in a second
 * view model so the start destination has one source that cannot disagree with itself.
 */
@HiltViewModel
class AuthStateViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService,
    private val userRepository: UserRepository,
    private val childInfoRepository: ChildInfoRepository,
    private val petRepository: PetRepository
) : ViewModel() {

    private val _isAuthenticated = MutableStateFlow<Boolean?>(null)
    val isAuthenticated: StateFlow<Boolean?> = _isAuthenticated.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Whether the first-run questionnaire still has to run, or null while the answer is unknown.
     *
     * Null must route to the loading screen, never to Home: flashing the dashboard and then
     * replacing it with a questionnaire is worse than a moment's spinner.
     */
    private val _needsOnboarding = MutableStateFlow<Boolean?>(null)
    val needsOnboarding: StateFlow<Boolean?> = _needsOnboarding.asStateFlow()

    init {
        checkAuthState()
    }

    /**
     * Checks the current authentication state, and — when there is an account — whether it still
     * needs onboarding. Both are published before [isLoading] clears, so no caller ever sees a
     * resolved authentication with an unresolved questionnaire.
     */
    private fun checkAuthState() {
        viewModelScope.launch {
            _isLoading.value = true
            _needsOnboarding.value = null

            // Wait for authentication to be ready, then check state
            val user = firebaseAuthService.waitForAuthReady()
            _isAuthenticated.value = user != null
            _needsOnboarding.value = user?.uid?.let { resolveOnboarding(it) } ?: false
            _isLoading.value = false
        }
    }

    /**
     * Whether [uid] still has to be walked through the questionnaire.
     *
     * Waits for the account's Room row rather than reading once, because on the launch right
     * after a sign-up the row is still being written: `ensureProfile` runs asynchronously off
     * the auth-state boundary, and a single read that happened to land first would report "no
     * user", which [OnboardingState] reads as "nobody to ask" — sending a brand-new parent
     * straight past the wizard they exist to see. The wait is bounded by the same
     * [ONBOARDING_LOAD_TIMEOUT_MILLIS] `ProfileViewModel` uses; timing out falls back to not
     * asking, because the one thing worse than missing the wizard is trapping a signed-in user
     * on it with no account behind it.
     *
     * A failure is treated the same way, and deliberately not surfaced: this decides a
     * navigation route, and there is no screen yet on which to show an error about it.
     */
    private suspend fun resolveOnboarding(uid: String): Boolean = try {
        withTimeoutOrNull(ONBOARDING_LOAD_TIMEOUT_MILLIS) {
            val account = userRepository.observeUserById(uid).first { it != null }
            val hasChildInfo = childInfoRepository.getAllChildInfo().first().isNotEmpty()
            // A pet counts as evidence too. Counting children alone handed the questionnaire to
            // a pets-only family on every launch.
            val hasPets = petRepository.getAllPets().first().isNotEmpty()
            OnboardingState.isNeeded(account, hasChildInfo, hasPets)
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        android.util.Log.e(TAG, "Failed to decide whether onboarding is needed for $uid", e)
        false
    }

    /**
     * Refreshes the authentication state.
     * Call this after login/logout operations.
     */
    fun refreshAuthState() {
        checkAuthState()
    }

    /**
     * Signs out the current user from Firebase authentication.
     * This should be called when the user wants to sign out of the app completely.
     */
    fun signOut() {
        viewModelScope.launch {
            firebaseAuthService.signOutCompletely()
            refreshAuthState()
        }
    }

    private companion object {
        const val TAG = "AuthStateViewModel"

        /** Same value `ProfileViewModel`'s own wait for the signed-in user's row uses. */
        const val ONBOARDING_LOAD_TIMEOUT_MILLIS = 5000L
    }
}
