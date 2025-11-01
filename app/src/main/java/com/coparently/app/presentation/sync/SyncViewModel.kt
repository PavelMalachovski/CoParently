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
    fun handleSignInResult(account: GoogleSignInAccount?) {
        viewModelScope.launch {
            if (account != null) {
                val token = googleSignInService.getAccessToken(account)
                if (token != null) {
                    _isSignedIn.value = true
                    encryptedPreferences.putSyncEnabled(true)
                    _isSyncEnabled.value = true
                    _syncState.value = SyncState.SignedIn
                } else {
                    _syncState.value = SyncState.Error("Failed to get access token")
                }
            } else {
                _syncState.value = SyncState.Error("Sign-in failed")
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
                        _syncState.value = SyncState.Syncing("Syncing...")(result.message)
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
                        _syncState.value = SyncState.Syncing("Syncing...")(result.message)
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

