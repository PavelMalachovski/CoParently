package com.coparently.app.presentation.guests

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.R
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.domain.guests.GuestInviteUri
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.repository.GuestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the guest-accept screen shows.
 *
 * @property code What the user typed, or what a `coplanly://guest` link pre-filled
 * @property isBusy A redemption is in flight
 * @property errorRes The one message to show, or null
 * @property acceptedUntilMillis Set once the grant exists; the instant it ends. Non-null is
 *   the screen's only "done" signal, and it carries the end date rather than a bare `true`
 *   because the first thing a guest should be told is how long this lasts
 */
data class GuestAcceptUiState(
    val code: String = "",
    val isBusy: Boolean = false,
    @StringRes val errorRes: Int? = null,
    val acceptedUntilMillis: Long? = null
)

/**
 * Redeems a guest invitation.
 *
 * Deliberately a separate ViewModel from `PairingViewModel` rather than another mode of it.
 * That one owns pairing — a link that assigns parent slots, creates a conversation and can
 * raise a custody conflict — and none of it should be one `if` away from a grandmother's
 * read-only access to one child.
 */
@HiltViewModel
class GuestAcceptViewModel @Inject constructor(
    private val guestRepository: GuestRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuestAcceptUiState())
    val uiState: StateFlow<GuestAcceptUiState> = _uiState.asStateFlow()

    /**
     * Pre-fills [code] from a `coplanly://guest` deep link, once.
     *
     * Ignored if the user has already typed something, for the reason `PairingDeepLinkEffect`
     * documents at the navigation level: a link arriving must never silently overwrite what
     * somebody is in the middle of entering.
     */
    fun prefill(code: String?) {
        val extracted = code?.let { GuestInviteUri.extractCode(it) } ?: return
        if (_uiState.value.code.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(code = extracted)
    }

    /** Records typed or pasted input; a pasted link is reduced to its code. */
    fun onCodeChanged(raw: String) {
        val code = GuestInviteUri.extractCode(raw) ?: raw.trim().uppercase()
        _uiState.value = _uiState.value.copy(code = code, errorRes = null)
    }

    /** Redeems the current code. */
    fun accept() {
        val code = _uiState.value.code
        if (code.isBlank() || _uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, errorRes = null)
        viewModelScope.launch {
            val result = guestRepository.acceptGuestInvite(code)
            _uiState.value = result.fold(
                onSuccess = { accepted ->
                    _uiState.value.copy(
                        isBusy = false,
                        acceptedUntilMillis = accepted.expiresAtMillis
                    )
                },
                onFailure = { failure ->
                    _uiState.value.copy(isBusy = false, errorRes = messageFor(failure))
                }
            )
        }
    }

    /**
     * The one message for a failed redemption.
     *
     * The four guest-specific cases each say something a user can act on, which is why they
     * are not folded into the generic "that code did not work". "This is a co-parent
     * invitation" tells somebody they are on the wrong screen; "the access has already ended"
     * tells them to ask for a new invitation rather than re-typing the same code.
     */
    @StringRes
    private fun messageFor(throwable: Throwable): Int =
        when ((throwable as? PairingException)?.error) {
            PairingError.NotFound -> R.string.pairing_error_not_found
            PairingError.Expired -> R.string.pairing_error_expired
            PairingError.NotPending -> R.string.pairing_error_not_pending
            PairingError.SelfPairing -> R.string.pairing_error_self_pairing
            PairingError.WrongRecipient -> R.string.pairing_error_wrong_recipient
            PairingError.Network -> R.string.pairing_error_network
            PairingError.NotGuestInvitation -> R.string.guest_error_not_guest_invitation
            PairingError.GrantEnded -> R.string.guest_error_grant_ended
            PairingError.InviterNotEntitled -> R.string.guest_error_inviter_not_entitled
            PairingError.AlreadyEntitled -> R.string.guest_error_already_entitled
            else -> R.string.pairing_error_unknown
        }
}
