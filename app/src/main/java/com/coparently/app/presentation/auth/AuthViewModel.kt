package com.coparently.app.presentation.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.google.CredentialAuthenticator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the login screen: the four ways in, and why the last attempt failed.
 *
 * Every credential operation takes the [Activity] as a parameter. Credential Manager hosts its UI
 * on one, and a ViewModel outlives an Activity — storing the reference would leak it.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService,
    private val credentialAuthenticator: CredentialAuthenticator,
    private val analyticsManager: AnalyticsManager,
    private val crashlyticsManager: CrashlyticsManager
) : ViewModel() {

    /** Invoked after any successful authentication so the host can refresh its auth state. */
    var onAuthStateChanged: (() -> Unit)? = null

    /** Invoked after authentication paths that navigate on their own. */
    var onAuthSuccess: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** @param email New email field value */
    fun updateEmail(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    /** @param password New password field value */
    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    /** Flips between the sign-in and sign-up presentations of the same form. */
    fun toggleSignInMode() {
        _uiState.update { it.copy(isSignInMode = !it.isSignInMode, error = null) }
    }

    /**
     * Signs in with the typed email and password, then offers to remember them.
     *
     * @param activity Activity to host the save prompt on
     * @param onSuccess Runs after the save prompt has been dealt with — see [completeSignIn]
     */
    fun signIn(activity: Activity, onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = AuthError.EmptyFields) }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            firebaseAuthService.signInWithEmail(state.email, state.password).fold(
                onSuccess = {
                    analyticsManager.logLogin("email")
                    completeSignIn(activity, state.email, state.password, onSuccess)
                },
                onFailure = { error -> reportEmailFailure(error, "sign_in", state.email) }
            )
        }
    }

    /**
     * Creates an account with the typed email and password, then offers to remember them.
     *
     * @param activity Activity to host the save prompt on
     * @param onSuccess Runs after the save prompt has been dealt with
     */
    fun signUp(activity: Activity, onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = AuthError.EmptyFields) }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            firebaseAuthService.createAccountWithEmail(state.email, state.password).fold(
                onSuccess = {
                    analyticsManager.logSignUp("email")
                    completeSignIn(activity, state.email, state.password, onSuccess)
                },
                onFailure = { error -> reportEmailFailure(error, "sign_up", state.email) }
            )
        }
    }

    /**
     * Runs Google's full-screen sign-in and exchanges the id token for a Firebase session.
     *
     * Reports its own failures into [uiState] and returns nothing. It used to return a `Result`
     * that the screen reported a second time, overwriting the mapped message with the raw
     * exception text — removing the second reporter is the fix.
     *
     * @param activity Activity to host Google's UI on
     */
    suspend fun signInWithGoogle(activity: Activity) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        credentialAuthenticator.signInWithGoogle(activity).fold(
            onSuccess = { idToken ->
                firebaseAuthService.signInWithGoogleIdToken(idToken).fold(
                    onSuccess = {
                        analyticsManager.logLogin("google")
                        _uiState.update { it.copy(isLoading = false) }
                        onAuthStateChanged?.invoke()
                        onAuthSuccess?.invoke()
                    },
                    onFailure = { error -> reportCredentialFailure(error, "firebase_google_auth") }
                )
            },
            onFailure = { error -> reportCredentialFailure(error, "google_sign_in") }
        )
    }

    /**
     * Offers the passwords the system has stored, and signs in with the chosen one.
     *
     * Feeds the result into the same [FirebaseAuthService.signInWithEmail] path the typed form
     * uses, so there is no second authentication route to keep in step. Deliberately does **not**
     * offer to save the password afterwards: it came from the store it would be saved back into.
     *
     * @param activity Activity to host the picker on
     */
    suspend fun signInWithSavedPassword(activity: Activity) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        credentialAuthenticator.getSavedPassword(activity).fold(
            onSuccess = { saved ->
                firebaseAuthService.signInWithEmail(saved.email, saved.password).fold(
                    onSuccess = {
                        analyticsManager.logLogin("saved_password")
                        _uiState.update { it.copy(isLoading = false, email = saved.email) }
                        onAuthStateChanged?.invoke()
                        onAuthSuccess?.invoke()
                    },
                    onFailure = { error ->
                        crashlyticsManager.recordExceptionWithContext(
                            error,
                            mapOf("action" to "saved_password_sign_in")
                        )
                        _uiState.update {
                            it.copy(isLoading = false, error = AuthError.fromEmailPassword(error))
                        }
                    }
                )
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = AuthError.fromSavedPassword(error))
                }
            }
        )
    }

    /**
     * Offers to remember the credentials, **then** hands control to [onSuccess].
     *
     * The order is the point. Navigating first puts the system's save sheet over the home
     * screen, attached to a screen that has nothing to do with it. `savePassword` never throws,
     * so a decline cannot strand the user on the login screen after Firebase has already
     * accepted them.
     */
    private suspend fun completeSignIn(
        activity: Activity,
        email: String,
        password: String,
        onSuccess: () -> Unit
    ) {
        credentialAuthenticator.savePassword(activity, email, password)
        _uiState.update { it.copy(isLoading = false) }
        onAuthStateChanged?.invoke()
        onSuccess()
    }

    private fun reportEmailFailure(error: Throwable, action: String, email: String) {
        crashlyticsManager.recordExceptionWithContext(
            error,
            mapOf("action" to action, "email" to email)
        )
        _uiState.update { it.copy(isLoading = false, error = AuthError.fromEmailPassword(error)) }
    }

    private fun reportCredentialFailure(error: Throwable, action: String) {
        val mapped = AuthError.fromGoogle(error)
        // A null mapping means the user dismissed the sheet. That is a decision, not a fault, and
        // recording it would bury real failures under a stream of cancellations.
        if (mapped != null) {
            crashlyticsManager.recordExceptionWithContext(error, mapOf("action" to action))
        }
        _uiState.update { it.copy(isLoading = false, error = mapped) }
    }
}

/**
 * UI state for the authentication screen.
 *
 * @property email Current email field value
 * @property password Current password field value
 * @property isSignInMode True for sign-in, false for sign-up
 * @property isLoading Whether an attempt is in flight
 * @property error Why the last attempt failed, or null. A *typed* reason rather than a sentence:
 *   the string is resolved in the composable, because a ViewModel has no `Context` and must not
 *   be given one.
 */
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isSignInMode: Boolean = true,
    val isLoading: Boolean = false,
    val error: AuthError? = null
)
