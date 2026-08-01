package com.coparently.app.presentation.pairing

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.utils.ValidationResult
import com.coparently.app.utils.ValidationUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transient state of the pairing form — everything that is not the pairing
 * itself. Errors are string resource ids, never English literals, so the
 * screen renders them in the user's language.
 */
data class PairingFormState(
    val codeInput: String = "",
    val emailInput: String = "",
    val isBusy: Boolean = false,
    @StringRes val errorRes: Int? = null,
    @StringRes val emailErrorRes: Int? = null,
    val qrBitmap: Bitmap? = null,
    val showQrDialog: Boolean = false
)

/**
 * ViewModel for the pairing screen: exposes the realtime [PairingState] from
 * the repository plus the local form state, and forwards the five actions.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val qrCodeService: QRCodeService,
    private val analyticsManager: AnalyticsManager
) : ViewModel() {

    val state: StateFlow<PairingState> = pairingRepository.observePairingState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PairingState.Loading)

    private val _form = MutableStateFlow(PairingFormState())
    val form: StateFlow<PairingFormState> = _form.asStateFlow()

    init {
        refreshInvite()
    }

    /** Ensures an invite code exists so the hero card always has one to show. */
    fun refreshInvite() {
        viewModelScope.launch { pairingRepository.createOrReuseInviteCode() }
    }

    /** Withdraws the current code and issues a fresh one. */
    fun regenerateInvite() {
        viewModelScope.launch {
            pairingRepository.revokeActiveInvite()
            pairingRepository.createOrReuseInviteCode()
        }
    }

    /**
     * Accepts typed or pasted input: a bare code, a full pairing URI, or share
     * text containing one. Anything else is kept as up-cased characters so the
     * user can keep typing.
     */
    fun onCodeInputChange(raw: String) {
        val code = PairingUri.extractCode(raw)
            ?: raw.trim().uppercase().filter { it in InviteCodeGenerator.ALPHABET }
                .take(InviteCodeGenerator.LENGTH)
        _form.value = _form.value.copy(codeInput = code, errorRes = null)
    }

    fun onEmailInputChange(email: String) {
        _form.value = _form.value.copy(emailInput = email, emailErrorRes = null, errorRes = null)
    }

    /** Redeems the code currently in the input field. */
    fun redeemCode() {
        val code = _form.value.codeInput
        if (!InviteCodeGenerator.isValid(code)) {
            _form.value = _form.value.copy(errorRes = R.string.pairing_error_code_incomplete)
            return
        }
        launchAction { pairingRepository.redeem(code) }
    }

    fun sendEmailInvitation() {
        val email = _form.value.emailInput
        val validation = ValidationUtils.validateEmail(email)
        if (validation is ValidationResult.Error) {
            _form.value = _form.value.copy(emailErrorRes = R.string.pairing_error_invalid_email)
            return
        }
        launchAction(onSuccess = { _form.value = _form.value.copy(emailInput = "") }) {
            pairingRepository.sendEmailInvitation(email).also { analyticsManager.logInvitationSent() }
        }
    }

    fun acceptIncoming(invitationId: String) = launchAction(
        onSuccess = { analyticsManager.logInvitationAccepted() }
    ) { pairingRepository.acceptIncoming(invitationId) }

    fun rejectIncoming(invitationId: String) =
        launchAction { pairingRepository.rejectIncoming(invitationId) }

    fun unpair() = launchAction { pairingRepository.unpair() }

    /** Renders the active invite's link as a QR bitmap and opens the dialog. */
    fun showQr() {
        val invite = (state.value as? PairingState.NotPaired)?.activeInvite ?: return
        viewModelScope.launch {
            val bitmap = qrCodeService.generatePairingQRCode(
                invitationId = PairingUri.build(invite.code),
                inviterName = invite.fromUserName,
                inviterEmail = invite.fromUserEmail
            )
            _form.value = _form.value.copy(qrBitmap = bitmap, showQrDialog = bitmap != null)
        }
    }

    fun dismissQr() {
        _form.value = _form.value.copy(showQrDialog = false, qrBitmap = null)
    }

    fun clearError() {
        _form.value = _form.value.copy(errorRes = null, emailErrorRes = null)
    }

    private fun launchAction(
        onSuccess: () -> Unit = {},
        action: suspend () -> Result<*>
    ) {
        _form.value = _form.value.copy(isBusy = true, errorRes = null)
        viewModelScope.launch {
            val result = action()
            _form.value = _form.value.copy(
                isBusy = false,
                errorRes = result.exceptionOrNull()?.let { messageFor(it) }
            )
            if (result.isSuccess) onSuccess()
        }
    }

    @StringRes
    private fun messageFor(throwable: Throwable): Int =
        when ((throwable as? PairingException)?.error) {
            PairingError.NotFound -> R.string.pairing_error_not_found
            PairingError.Expired -> R.string.pairing_error_expired
            PairingError.NotPending -> R.string.pairing_error_not_pending
            PairingError.SelfPairing -> R.string.pairing_error_self_pairing
            PairingError.AlreadyPaired -> R.string.pairing_error_already_paired
            PairingError.WrongRecipient -> R.string.pairing_error_wrong_recipient
            PairingError.Network -> R.string.pairing_error_network
            else -> R.string.pairing_error_unknown
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
