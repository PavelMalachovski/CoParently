package com.coparently.app.presentation.sync

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.remote.google.GoogleSignInService
import com.coparently.app.data.sync.CalendarSyncRepository
import com.coparently.app.data.sync.SyncResult
import com.coparently.app.data.sync.SyncService
import com.coparently.app.data.sync.SyncStatus
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing synchronization operations.
 * Handles both Firestore sync and Google Calendar sync.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncService: SyncService,
    private val calendarSyncRepository: CalendarSyncRepository,
    private val googleSignInService: GoogleSignInService
) : ViewModel() {

    // Google Calendar sync state
    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _isSyncEnabled = MutableStateFlow(false)
    val isSyncEnabled: StateFlow<Boolean> = _isSyncEnabled.asStateFlow()

    private val _syncState = MutableStateFlow<GoogleCalendarSyncState>(GoogleCalendarSyncState.Idle)
    val syncState: StateFlow<GoogleCalendarSyncState> = _syncState.asStateFlow()

    private val _currentAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val currentAccount: StateFlow<GoogleSignInAccount?> = _currentAccount.asStateFlow()

    // Firestore sync status
    val firestoreSyncStatus: StateFlow<SyncStatus> = syncService.syncStatus

    init {
        checkSignInStatus()
    }

    /**
     * Checks current Google Sign-In status.
     */
    private fun checkSignInStatus() {
        val account = googleSignInService.getLastSignedInAccount()
        _isSignedIn.value = account != null
        _currentAccount.value = account
    }

    /**
     * Gets the Google Sign-In intent.
     */
    fun getSignInIntent(): Intent {
        return googleSignInService.googleSignInClient.signInIntent
    }

    /**
     * Gets the Google Sign-In intent with Calendar scope.
     */
    fun getSignInIntentWithScope(): Intent {
        return googleSignInService.getSignInIntentWithScope()
    }

    /**
     * Checks if the current account has calendar scope.
     */
    fun hasCalendarScope(): Boolean {
        val account = _currentAccount.value ?: return false
        return googleSignInService.hasCalendarScope(account)
    }

    /**
     * Handles Google Sign-In result.
     */
    fun handleSignInResult(account: GoogleSignInAccount) {
        viewModelScope.launch {
            _currentAccount.value = account
            _isSignedIn.value = true

            // Try to get access token to verify authentication
            val (token, error) = googleSignInService.getAccessToken(account)
            if (token != null) {
                _syncState.value = GoogleCalendarSyncState.Success("Signed in successfully")
            } else {
                _syncState.value = GoogleCalendarSyncState.Error(
                    error ?: "Failed to get access token"
                )
            }
        }
    }

    /**
     * Toggles Google Calendar sync enabled state.
     */
    fun toggleSync(enabled: Boolean) {
        _isSyncEnabled.value = enabled
        if (enabled) {
            syncFromGoogle()
        }
    }

    /**
     * Syncs events from Google Calendar.
     */
    fun syncFromGoogle() {
        if (!_isSignedIn.value) {
            _syncState.value = GoogleCalendarSyncState.Error("Not signed in to Google")
            return
        }

        viewModelScope.launch {
            calendarSyncRepository.syncFromGoogle().collect { result ->
                _syncState.value = when (result) {
                    is SyncResult.Progress -> GoogleCalendarSyncState.Syncing(result.message)
                    is SyncResult.Success -> GoogleCalendarSyncState.Success(result.message)
                    is SyncResult.Error -> GoogleCalendarSyncState.Error(result.message)
                }
            }
        }
    }

    /**
     * Signs out from Google.
     */
    fun signOut() {
        viewModelScope.launch {
            googleSignInService.signOut()
            _isSignedIn.value = false
            _isSyncEnabled.value = false
            _syncState.value = GoogleCalendarSyncState.Idle
            _currentAccount.value = null
        }
    }

    /**
     * Performs full Firestore synchronization.
     */
    fun performFirestoreSync() {
        viewModelScope.launch {
            syncService.performFullSync()
        }
    }
}

/**
 * State representing Google Calendar sync operation status.
 */
sealed class GoogleCalendarSyncState {
    data object Idle : GoogleCalendarSyncState()
    data class Syncing(val message: String) : GoogleCalendarSyncState()
    data class Success(val message: String) : GoogleCalendarSyncState()
    data class Error(val message: String) : GoogleCalendarSyncState()
}
