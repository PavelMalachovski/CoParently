package com.coparently.app.presentation.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.google.CredentialAuthenticator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
     * This is a `suspend` function launched from `rememberCoroutineScope()` in `AuthScreen`,
     * which is cancelled the moment the composable leaves composition — a rotation, a
     * split-screen resize, or the automatic dark/light switch, all of which can happen while
     * Google's full-screen sign-in is in front. The `try`/`finally` is what clears [isLoading]
     * on that path: a `CancellationException` at the `getCredential` suspension point must keep
     * propagating (never caught here), so `finally` is the only block guaranteed to run on both
     * the normal and the cancelled path. `AuthViewModel` survives the recreation this can trigger,
     * so without it every control on the screen would stay disabled until the app is force-stopped.
     *
     * @param activity Activity to host Google's UI on
     */
    suspend fun signInWithGoogle(activity: Activity) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            credentialAuthenticator.signInWithGoogle(activity).fold(
                onSuccess = { idToken ->
                    firebaseAuthService.signInWithGoogleIdToken(idToken).fold(
                        onSuccess = {
                            analyticsManager.logLogin("google")
                            onAuthStateChanged?.invoke()
                            onAuthSuccess?.invoke()
                        },
                        onFailure = { error -> reportCredentialFailure(error, "firebase_google_auth") }
                    )
                },
                onFailure = { error -> reportCredentialFailure(error, "google_sign_in") }
            )
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Offers the passwords the system has stored, and signs in with the chosen one.
     *
     * Feeds the result into the same [FirebaseAuthService.signInWithEmail] path the typed form
     * uses, so there is no second authentication route to keep in step. Deliberately does **not**
     * offer to save the password afterwards: it came from the store it would be saved back into.
     *
     * Like [signInWithGoogle], this is a `suspend` function launched from the composition-scoped
     * `rememberCoroutineScope()` in `AuthScreen`, so the same recreation risk applies: a
     * `CancellationException` at the `getCredential` suspension point must propagate, but it
     * would otherwise skip every `isLoading = false` in this body. `finally` clears it on the
     * cancelled path too, so a config change mid-lookup cannot leave the screen locked.
     *
     * @param activity Activity to host the picker on
     */
    suspend fun signInWithSavedPassword(activity: Activity) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            credentialAuthenticator.getSavedPassword(activity).fold(
                onSuccess = { saved ->
                    firebaseAuthService.signInWithEmail(saved.email, saved.password).fold(
                        onSuccess = {
                            analyticsManager.logLogin("saved_password")
                            _uiState.update { it.copy(email = saved.email) }
                            onAuthStateChanged?.invoke()
                            onAuthSuccess?.invoke()
                        },
                        onFailure = { error -> reportSavedPasswordFirebaseFailure(error) }
                    )
                },
                onFailure = { error -> reportSavedPasswordCredentialFailure(error) }
            )
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
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
        rethrowIfCancellation(error)
        crashlyticsManager.recordExceptionWithContext(
            error,
            mapOf("action" to action, "email" to email)
        )
        _uiState.update { it.copy(isLoading = false, error = AuthError.fromEmailPassword(error)) }
    }

    private fun reportCredentialFailure(error: Throwable, action: String) {
        rethrowIfCancellation(error)
        val mapped = AuthError.fromGoogle(error)
        // A null mapping means the user dismissed the sheet. That is a decision, not a fault, and
        // recording it would bury real failures under a stream of cancellations.
        if (mapped != null) {
            crashlyticsManager.recordExceptionWithContext(error, mapOf("action" to action))
        }
        _uiState.update { it.copy(error = mapped) }
    }

    /**
     * Reports a genuine [FirebaseAuthService.signInWithEmail] failure reached via the
     * saved-password button.
     *
     * `FirebaseAuthService` catches `Exception` broadly and turns even a cancellation into a
     * `Result.failure`, so [rethrowIfCancellation] runs first: a config change that cancels this
     * call must keep unwinding as a cancellation, not surface as a bogus sign-in failure.
     */
    private fun reportSavedPasswordFirebaseFailure(error: Throwable) {
        rethrowIfCancellation(error)
        crashlyticsManager.recordExceptionWithContext(
            error,
            mapOf("action" to "saved_password_sign_in")
        )
        _uiState.update { it.copy(error = AuthError.fromEmailPassword(error)) }
    }

    /**
     * Reports a genuine [CredentialAuthenticator.getSavedPassword] failure — no saved password,
     * no Play services, or a misconfigured provider.
     *
     * Mirrors [reportCredentialFailure]'s shape: Crashlytics only sees it when
     * [AuthError.fromSavedPassword] maps the cause to something non-null. A null mapping means
     * the user dismissed the picker, which is a decision, not a fault worth burying real
     * failures under.
     */
    private fun reportSavedPasswordCredentialFailure(error: Throwable) {
        val mapped = AuthError.fromSavedPassword(error)
        if (mapped != null) {
            crashlyticsManager.recordExceptionWithContext(
                error,
                mapOf("action" to "saved_password_credential")
            )
        }
        _uiState.update { it.copy(error = mapped) }
    }

    /**
     * Rethrows [error] when it is a coroutine [CancellationException] instead of treating it as
     * an authentication failure.
     *
     * [FirebaseAuthService] catches `Exception` broadly and converts every failure, cancellation
     * included, into a `Result.failure` — correct in isolation, but every Firebase call in this
     * ViewModel now runs from a coroutine a configuration change can cancel mid-flight, so the
     * cancellation crosses that boundary disguised as an ordinary failure. Rethrowing here
     * restores structured cancellation instead of filing a bogus Crashlytics report and showing
     * an error the user never actually saw happen.
     */
    private fun rethrowIfCancellation(error: Throwable) {
        if (error is CancellationException) throw error
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
