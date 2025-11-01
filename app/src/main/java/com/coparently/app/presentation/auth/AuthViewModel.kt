package com.coparently.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for authentication screen.
 * Manages login and registration state.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email, errorMessage = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun toggleSignInMode() {
        _uiState.value = _uiState.value.copy(
            isSignInMode = !_uiState.value.isSignInMode,
            errorMessage = null
        )
    }

    fun signIn(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Please fill in all fields")
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val result = firebaseAuthService.signInWithEmail(state.email, state.password)
            result.fold(
                onSuccess = {
                    _uiState.value = state.copy(isLoading = false)
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.value = state.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Sign in failed"
                    )
                }
            )
        }
    }

    fun signUp(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Please fill in all fields")
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val result = firebaseAuthService.createAccountWithEmail(state.email, state.password)
            result.fold(
                onSuccess = {
                    _uiState.value = state.copy(isLoading = false)
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.value = state.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Sign up failed"
                    )
                }
            )
        }
    }
}

/**
 * UI state for authentication screen.
 */
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isSignInMode: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

