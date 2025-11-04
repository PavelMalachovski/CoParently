package com.coparently.app.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.sync.SyncService
import com.coparently.app.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing synchronization operations.
 * Observes sync status and provides actions for triggering sync.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncService: SyncService
) : ViewModel() {

    // Existing fields for Google Calendar sync
    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _isSyncEnabled = MutableStateFlow(false)
    val isSyncEnabled: StateFlow<Boolean> = _isSyncEnabled.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // New field for Firestore sync status
    val firestoreSyncStatus: StateFlow<SyncStatus> = syncService.syncStatus

    /**
     * Performs full synchronization with Firestore.
     */
    fun performSync() {
        viewModelScope.launch {
            syncService.performFullSync()
        }
    }

    /**
     * Handles Google Sign-In result.
     * This is for Google Calendar sync.
     */
    fun handleSignInResult(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        _isSignedIn.value = true
        // Additional handling for Google Calendar sync
    }

    /**
     * Toggles sync enabled state.
     */
    fun toggleSync(enabled: Boolean) {
        _isSyncEnabled.value = enabled
        if (enabled) {
            performSync()
        }
    }

    /**
     * Signs out from Google.
     */
    fun signOut() {
        _isSignedIn.value = false
        _isSyncEnabled.value = false
        _syncState.value = SyncState.Idle
    }
}

/**
 * State representing sync operation status for Google Calendar.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}
