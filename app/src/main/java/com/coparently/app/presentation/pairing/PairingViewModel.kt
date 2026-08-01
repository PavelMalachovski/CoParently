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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transient state of the pairing form — everything that is not the pairing
 * itself. Errors are string resource ids, never English literals, so the
 * screen renders them in the user's language.
 *
 * Errors are split by where they belong: [codeErrorRes] and [emailErrorRes]
 * sit under their respective input field (validation, or a redeem/invite
 * failure that concerns exactly what the user typed there), while
 * [actionErrorRes] is for actions with no associated field — accepting or
 * declining an incoming invite, unpairing, regenerating a code — and is
 * meant to be surfaced once (e.g. a snackbar) and then cleared with
 * [PairingViewModel.consumeActionError]. Without this split, a failed
 * `unpair()` — which has no field to attach to — would have nowhere to
 * show and the button would look dead.
 */
data class PairingFormState(
    val codeInput: String = "",
    val emailInput: String = "",
    val isBusy: Boolean = false,
    @StringRes val codeErrorRes: Int? = null,
    @StringRes val emailErrorRes: Int? = null,
    @StringRes val actionErrorRes: Int? = null,
    val qrBitmap: Bitmap? = null,
    val showQrDialog: Boolean = false
)

/**
 * ViewModel for the pairing screen: exposes the realtime [PairingState] from
 * the repository plus the local form state, and forwards its actions.
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
        viewModelScope.launch {
            // Only mint a code once the account is known to be unpaired. Minting one
            // unconditionally left an already-paired account holding a live 24-hour invite:
            // harmless while the callable rejects it, but the document stays redeemable and
            // becomes live again the moment the user unpairs.
            state.first { it !is PairingState.Loading }
            if (state.value is PairingState.NotPaired) refreshInvite()
        }
    }

    /** Ensures an invite code exists so the hero card always has one to show. */
    fun refreshInvite() {
        viewModelScope.launch { pairingRepository.createOrReuseInviteCode() }
    }

    /**
     * Withdraws the current code and issues a fresh one. Has no field of its
     * own, so a failure surfaces through [PairingFormState.actionErrorRes].
     */
    fun regenerateInvite() = launchAction {
        val revoked = pairingRepository.revokeActiveInvite()
        if (revoked.isSuccess) pairingRepository.createOrReuseInviteCode() else revoked
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
        _form.value = _form.value.copy(codeInput = code, codeErrorRes = null)
    }

    /** Updates the email-invite field, clearing its previous validation error. */
    fun onEmailInputChange(email: String) {
        _form.value = _form.value.copy(emailInput = email, emailErrorRes = null)
    }

    /** Redeems the code currently in the input field. */
    fun redeemCode() {
        val code = _form.value.codeInput
        if (!InviteCodeGenerator.isValid(code)) {
            _form.value = _form.value.copy(codeErrorRes = R.string.pairing_error_code_incomplete)
            return
        }
        launchAction(
            onError = { res -> _form.value = _form.value.copy(codeErrorRes = res) }
        ) { pairingRepository.redeem(code) }
    }

    /** Sends an email invitation to the address currently in the email field. */
    fun sendEmailInvitation() {
        val email = _form.value.emailInput
        val validation = ValidationUtils.validateEmail(email)
        if (validation is ValidationResult.Error) {
            _form.value = _form.value.copy(emailErrorRes = R.string.pairing_error_invalid_email)
            return
        }
        launchAction(
            onSuccess = {
                analyticsManager.logInvitationSent()
                _form.value = _form.value.copy(emailInput = "")
            },
            onError = { res -> _form.value = _form.value.copy(emailErrorRes = res) }
        ) { pairingRepository.sendEmailInvitation(email) }
    }

    /** Accepts an invitation addressed to this user. */
    fun acceptIncoming(invitationId: String) = launchAction(
        onSuccess = { analyticsManager.logInvitationAccepted() }
    ) { pairingRepository.acceptIncoming(invitationId) }

    /**
     * Declines an invitation addressed to this user. Has no field of its own,
     * so a failure surfaces through [PairingFormState.actionErrorRes].
     */
    fun rejectIncoming(invitationId: String) =
        launchAction { pairingRepository.rejectIncoming(invitationId) }

    /**
     * Ends the co-parent link. Has no field of its own, so a failure surfaces
     * through [PairingFormState.actionErrorRes] rather than being silent.
     */
    fun unpair() = launchAction { pairingRepository.unpair() }

    /** Renders the active invite's link as a QR bitmap and opens the dialog. */
    fun showQr() {
        val invite = (state.value as? PairingState.NotPaired)?.activeInvite ?: return
        viewModelScope.launch {
            val bitmap = qrCodeService.generatePairingQRCode(content = PairingUri.build(invite.code))
            _form.value = _form.value.copy(qrBitmap = bitmap, showQrDialog = bitmap != null)
        }
    }

    /** Closes the QR preview dialog and releases the bitmap. */
    fun dismissQr() {
        _form.value = _form.value.copy(showQrDialog = false, qrBitmap = null)
    }

    /** Clears the two field-level errors, e.g. when the user starts over. */
    fun clearError() {
        _form.value = _form.value.copy(codeErrorRes = null, emailErrorRes = null)
    }

    /** Marks a field-less action error as shown; call once it has been presented (e.g. a snackbar). */
    fun consumeActionError() {
        _form.value = _form.value.copy(actionErrorRes = null)
    }

    private fun launchAction(
        onSuccess: () -> Unit = {},
        onError: (Int) -> Unit = { res -> _form.value = _form.value.copy(actionErrorRes = res) },
        action: suspend () -> Result<*>
    ) {
        _form.value = _form.value.copy(isBusy = true, actionErrorRes = null)
        viewModelScope.launch {
            val result = action()
            _form.value = _form.value.copy(isBusy = false)
            val failure = result.exceptionOrNull()
            if (failure != null) onError(messageFor(failure)) else onSuccess()
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
