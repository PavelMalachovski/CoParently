package com.coparently.app.presentation.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.remote.firebase.CoParentPairingService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for pairing screen.
 * Manages co-parent pairing and invitation state.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingService: CoParentPairingService,
    private val firebaseAuthService: FirebaseAuthService,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    init {
        loadPairingInfo()
        loadPendingInvitations()
    }

    private fun loadPairingInfo() {
        viewModelScope.launch {
            val currentUser = userRepository.getCurrentUser()
            currentUser?.partnerId?.let { partnerId ->
                val partnerInfo = pairingService.getPartnerInfo(partnerId)
                _uiState.value = _uiState.value.copy(
                    partnerEmail = partnerInfo?.get("email") as? String
                )
            }
        }
    }

    private fun loadPendingInvitations() {
        viewModelScope.launch {
            val currentUser = firebaseAuthService.getCurrentUser()
            if (currentUser != null) {
                val result = pairingService.getPendingInvitations(currentUser.email ?: "")
                result.fold(
                    onSuccess = { invitations ->
                        _uiState.value = _uiState.value.copy(pendingInvitations = invitations)
                    },
                    onFailure = { /* Handle error */ }
                )
            }
        }
    }

    fun updateInvitationEmail(email: String) {
        _uiState.value = _uiState.value.copy(invitationEmail = email, errorMessage = null)
    }

    fun sendInvitation(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.invitationEmail.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Please enter an email")
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            val currentUser = firebaseAuthService.getCurrentUser()
            val currentUserData = userRepository.getCurrentUser()
            if (currentUser != null && currentUserData != null) {
                val result = pairingService.sendInvitation(
                    currentUser.uid,
                    currentUser.email ?: "",
                    currentUserData.name,
                    state.invitationEmail
                )
                result.fold(
                    onSuccess = {
                        _uiState.value = state.copy(isLoading = false)
                        onSuccess()
                    },
                    onFailure = { error ->
                        _uiState.value = state.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Failed to send invitation"
                        )
                    }
                )
            }
        }
    }

    fun acceptInvitation(invitationId: String) {
        viewModelScope.launch {
            val currentUser = firebaseAuthService.getCurrentUser()
            if (currentUser != null) {
                val result = pairingService.acceptInvitation(invitationId, currentUser.uid)
                result.fold(
                    onSuccess = {
                        loadPairingInfo()
                        loadPendingInvitations()
                    },
                    onFailure = { /* Handle error */ }
                )
            }
        }
    }

    fun rejectInvitation(invitationId: String) {
        viewModelScope.launch {
            val result = pairingService.rejectInvitation(invitationId)
            result.fold(
                onSuccess = {
                    loadPendingInvitations()
                },
                onFailure = { /* Handle error */ }
            )
        }
    }

    fun removePairing() {
        viewModelScope.launch {
            val currentUser = firebaseAuthService.getCurrentUser()
            val currentUserData = userRepository.getCurrentUser()
            if (currentUser != null && currentUserData?.partnerId != null) {
                val result = pairingService.removePartnership(
                    currentUser.uid,
                    currentUserData.partnerId
                )
                result.fold(
                    onSuccess = {
                        loadPairingInfo()
                    },
                    onFailure = { /* Handle error */ }
                )
            }
        }
    }
}

/**
 * UI state for pairing screen.
 */
data class PairingUiState(
    val invitationEmail: String = "",
    val partnerEmail: String? = null,
    val pendingInvitations: List<Map<String, Any?>> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

