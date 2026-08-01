package com.coparently.app.domain.model

/**
 * Why a pairing operation failed. Each case maps to exactly one message in the
 * UI, so the presentation layer never has to inspect exception text.
 */
sealed interface PairingError {

    /** No invitation matches the code or id. */
    data object NotFound : PairingError

    /** The invitation is past its expiry. */
    data object Expired : PairingError

    /** The invitation was already accepted, rejected or cancelled. */
    data object NotPending : PairingError

    /** The user tried to redeem their own invitation. */
    data object SelfPairing : PairingError

    /** One of the two accounts already has a co-parent. */
    data object AlreadyPaired : PairingError

    /** An email invitation addressed to somebody else. */
    data object WrongRecipient : PairingError

    /** Offline, timeout or an unreachable backend. */
    data object Network : PairingError

    /** Anything else; [message] is for logs, not for the user. */
    data class Unknown(val message: String?) : PairingError
}
