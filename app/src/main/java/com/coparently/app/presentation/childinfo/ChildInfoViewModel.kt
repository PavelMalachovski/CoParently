package com.coparently.app.presentation.childinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.repository.ChildInfoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing child information.
 * Handles CRUD operations and UI state for child info screens.
 */
@HiltViewModel
class ChildInfoViewModel @Inject constructor(
    private val childInfoRepository: ChildInfoRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val analyticsManager: AnalyticsManager,
    private val crashlyticsManager: CrashlyticsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChildInfoUiState>(ChildInfoUiState.Loading)
    val uiState: StateFlow<ChildInfoUiState> = _uiState.asStateFlow()

    private val _currentChildInfo = MutableStateFlow<ChildInfo?>(null)
    val currentChildInfo: StateFlow<ChildInfo?> = _currentChildInfo.asStateFlow()

    init {
        loadChildInfo()
    }

    /**
     * Loads all child information.
     */
    fun loadChildInfo() {
        viewModelScope.launch {
            _uiState.value = ChildInfoUiState.Loading
            try {
                childInfoRepository.getAllChildInfo().collect { childInfoList ->
                    _uiState.value = ChildInfoUiState.Success(childInfoList)
                    if (childInfoList.isNotEmpty()) {
                        _currentChildInfo.value = childInfoList.first()
                    }
                }
            } catch (e: Exception) {
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to load child info")
            }
        }
    }

    /**
     * Loads specific child information by ID.
     */
    fun loadChildInfoById(id: String) {
        viewModelScope.launch {
            childInfoRepository.observeChildInfoById(id).collect { childInfo ->
                _currentChildInfo.value = childInfo
            }
        }
    }

    /**
     * Creates or updates child information.
     *
     * Takes the whole [ChildInfo] rather than one parameter per field: the caller builds it by
     * copying the loaded snapshot ([currentChildInfo]), the same rule `AddEditEventScreen` follows
     * for events, so fields the form does not surface (sync/ownership stamps, and
     * [ChildInfo.medicalProfile] before this editor existed) are never silently reset to their
     * defaults on save.
     *
     * @param childInfo The child info to persist, with all form fields already applied via `copy()`
     * @param isNewChild Whether this call creates a brand-new child, for analytics only
     */
    fun upsertChildInfo(childInfo: ChildInfo, isNewChild: Boolean) {
        viewModelScope.launch {
            try {
                val currentUser = firebaseAuthService.getCurrentUser()
                    ?: throw IllegalStateException("User not authenticated")

                val finalChildInfo = childInfo.copy(
                    createdByFirebaseUid = childInfo.createdByFirebaseUid ?: currentUser.uid,
                    lastModifiedBy = currentUser.uid,
                    syncedToFirestore = false
                )

                childInfoRepository.upsertChildInfo(finalChildInfo)

                // Log analytics event
                if (isNewChild) {
                    analyticsManager.logChildInfoAdded()
                } else {
                    analyticsManager.logChildInfoUpdated()
                }
            } catch (e: Exception) {
                crashlyticsManager.recordExceptionWithContext(
                    e,
                    mapOf("action" to "upsert_child_info", "child_name" to childInfo.childName)
                )
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to save child info")
            }
        }
    }

    /**
     * Deletes child information.
     */
    fun deleteChildInfo(childInfo: ChildInfo) {
        viewModelScope.launch {
            try {
                childInfoRepository.deleteChildInfo(childInfo)
                analyticsManager.logChildInfoDeleted()
                loadChildInfo()
            } catch (e: Exception) {
                crashlyticsManager.recordExceptionWithContext(
                    e,
                    mapOf("action" to "delete_child_info", "child_id" to childInfo.id)
                )
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to delete child info")
            }
        }
    }

    /**
     * Syncs child information with Firestore.
     */
    fun syncChildInfo() {
        viewModelScope.launch {
            try {
                childInfoRepository.syncWithFirestore()
            } catch (e: Exception) {
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to sync child info")
            }
        }
    }
}

/**
 * UI state for child information screen.
 */
sealed class ChildInfoUiState {
    data object Loading : ChildInfoUiState()
    data class Success(val childInfoList: List<ChildInfo>) : ChildInfoUiState()
    data class Error(val message: String) : ChildInfoUiState()
}

