package com.coparently.app.presentation.childinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.model.Activity
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.Medication
import com.coparently.app.domain.model.SchoolInfo
import com.coparently.app.domain.repository.ChildInfoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for managing child information.
 * Handles CRUD operations and UI state for child info screens.
 */
@HiltViewModel
class ChildInfoViewModel @Inject constructor(
    private val childInfoRepository: ChildInfoRepository,
    private val firebaseAuthService: FirebaseAuthService
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
     */
    fun upsertChildInfo(
        id: String?,
        childName: String,
        dateOfBirth: LocalDateTime?,
        medications: List<Medication>,
        activities: List<Activity>,
        allergies: List<String>,
        medicalNotes: String?,
        emergencyContacts: List<EmergencyContact>,
        schoolInfo: SchoolInfo?
    ) {
        viewModelScope.launch {
            try {
                val currentUser = firebaseAuthService.getCurrentUser()
                    ?: throw IllegalStateException("User not authenticated")

                val now = LocalDateTime.now()
                val childInfo = ChildInfo(
                    id = id ?: UUID.randomUUID().toString(),
                    childName = childName,
                    dateOfBirth = dateOfBirth,
                    medications = medications,
                    activities = activities,
                    allergies = allergies,
                    medicalNotes = medicalNotes,
                    emergencyContacts = emergencyContacts,
                    schoolInfo = schoolInfo,
                    createdAt = _currentChildInfo.value?.createdAt ?: now,
                    updatedAt = now,
                    createdByFirebaseUid = _currentChildInfo.value?.createdByFirebaseUid ?: currentUser.uid,
                    lastModifiedBy = currentUser.uid,
                    syncedToFirestore = false
                )

                childInfoRepository.upsertChildInfo(childInfo)
            } catch (e: Exception) {
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
                loadChildInfo()
            } catch (e: Exception) {
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

