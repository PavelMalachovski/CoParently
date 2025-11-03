package com.coparently.app.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.remote.google.GoogleSignInService
import com.coparently.app.data.sync.CalendarSyncRepository
import com.coparently.app.data.sync.SyncResult
import com.coparently.app.domain.model.Event
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing Google Calendar synchronization.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val googleSignInService: GoogleSignInService,
    private val encryptedPreferences: EncryptedPreferences,
    private val syncRepository: CalendarSyncRepository
) : ViewModel() {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _isSyncEnabled = MutableStateFlow(false)
    val isSyncEnabled: StateFlow<Boolean> = _isSyncEnabled.asStateFlow()

    init {
        checkSignInStatus()
        checkSyncEnabled()
    }

    /**
     * Checks if user is signed in.
     */
    private fun checkSignInStatus() {
        _isSignedIn.value = googleSignInService.isSignedIn()
    }

    /**
     * Checks if sync is enabled.
     */
    private fun checkSyncEnabled() {
        _isSyncEnabled.value = encryptedPreferences.isSyncEnabled()
    }

    /**
     * Handles Google Sign-In result.
     */
    fun handleSignInResult(account: GoogleSignInAccount?, errorMessage: String? = null) {
        viewModelScope.launch {
            if (account != null) {
                // Check if calendar scope is granted
                if (!googleSignInService.hasCalendarScope(account)) {
                    _syncState.value = SyncState.Error(
                        "Calendar permission not granted. Please grant permission to access Google Calendar."
                    )
                    _isSignedIn.value = true // User is signed in, but needs to grant scope
                    return@launch
                }

                try {
                    val (token, tokenError) = googleSignInService.getAccessToken(account)
                    if (token != null) {
                        _isSignedIn.value = true
                        encryptedPreferences.putSyncEnabled(true)
                        _isSyncEnabled.value = true
                        _syncState.value = SyncState.SignedIn
                    } else {
                        // Use specific error message from getAccessToken if available
                        val detailedError = tokenError ?: errorMessage ?:
                            "Failed to get access token. " +
                            "Make sure Google Calendar API is enabled in Google Cloud Console " +
                            "and OAuth 2.0 Client ID is configured. Please try again."
                        _syncState.value = SyncState.Error(detailedError)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _syncState.value = SyncState.Error(
                        "Error getting access token: ${e.message ?: "Unknown error"}"
                    )
                }
            } else {
                _syncState.value = SyncState.Error(
                    errorMessage ?: "Sign-in failed. Please try again."
                )
            }
        }
    }

    /**
     * Syncs events from Google Calendar (pull).
     */
    fun syncFromGoogle() {
        viewModelScope.launch {
            if (!_isSignedIn.value) {
                _syncState.value = SyncState.Error("Not signed in")
                return@launch
            }

            _syncState.value = SyncState.Syncing("Syncing...")

            syncRepository.syncFromGoogle().collect { result ->
                when (result) {
                    is SyncResult.Progress -> {
                        _syncState.value = SyncState.Syncing(result.message)
                    }
                    is SyncResult.Success -> {
                        _syncState.value = SyncState.Success(result.message)
                    }
                    is SyncResult.Error -> {
                        _syncState.value = SyncState.Error(result.message)
                    }
                }
            }
        }
    }

    /**
     * Syncs event to Google Calendar (push).
     */
    fun syncToGoogle(event: Event) {
        viewModelScope.launch {
            if (!_isSignedIn.value) {
                _syncState.value = SyncState.Error("Not signed in")
                return@launch
            }

            syncRepository.syncToGoogle(event).collect { result ->
                when (result) {
                    is SyncResult.Progress -> {
                        _syncState.value = SyncState.Syncing(result.message)
                    }
                    is SyncResult.Success -> {
                        _syncState.value = SyncState.Success(result.message)
                    }
                    is SyncResult.Error -> {
                        _syncState.value = SyncState.Error(result.message)
                    }
                }
            }
        }
    }

    /**
     * Toggles sync enabled/disabled.
     */
    fun toggleSync(enabled: Boolean) {
        encryptedPreferences.putSyncEnabled(enabled)
        _isSyncEnabled.value = enabled

        if (!enabled) {
            viewModelScope.launch {
                googleSignInService.signOut()
                _isSignedIn.value = false
                _syncState.value = SyncState.Idle
            }
        }
    }

    /**
     * Gets Google Sign-In Intent for launching sign-in flow.
     */
    fun getSignInIntent() = googleSignInService.googleSignInClient.signInIntent

    /**
     * Checks if calendar scope is granted for the current account.
     */
    fun hasCalendarScope(): Boolean {
        val account = googleSignInService.getLastSignedInAccount()
        return account != null && googleSignInService.hasCalendarScope(account)
    }

    /**
     * Gets sign-in intent to re-authenticate with calendar scope.
     * Use this to request calendar permission by signing in again.
     */
    fun getSignInIntentWithScope() = googleSignInService.getSignInIntentWithScope()

    /**
     * Signs out from Google.
     */
    fun signOut() {
        viewModelScope.launch {
            googleSignInService.signOut()
            encryptedPreferences.putSyncEnabled(false)
            _isSignedIn.value = false
            _isSyncEnabled.value = false
            _syncState.value = SyncState.Idle
        }
    }

    /**
     * Signs out and prepares for re-sign in with scope.
     * Used when calendar permission is not granted.
     */
    fun signOutForScope() {
        viewModelScope.launch {
            googleSignInService.signOut()
            _isSignedIn.value = false
            _isSyncEnabled.value = false
            _syncState.value = SyncState.Idle
        }
    }
}

/**
 * UI state for synchronization.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data object SignedIn : SyncState()
    data class Syncing(val message: String = "Syncing...") : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}

