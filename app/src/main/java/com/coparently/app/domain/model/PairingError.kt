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

    /**
     * A guest invitation was offered to the co-parent pairing path.
     *
     * Not a malformed code: it is a perfectly good invitation of the other kind, and the
     * message that goes with it says so rather than telling the user their code is wrong.
     */
    data object GuestInvitation : PairingError

    /** A co-parent invitation was offered to the guest path — the same mistake, mirrored. */
    data object NotGuestInvitation : PairingError

    /**
     * A guest invitation whose access window ended before it was redeemed.
     *
     * Distinct from [Expired], which is the *offer* running out. Both mean "too late", but
     * only this one means the parent has to choose a new end date rather than just re-send.
     */
    data object GrantEnded : PairingError

    /** The person who made the guest invitation may not grant access to that record. */
    data object InviterNotEntitled : PairingError

    /** Offline, timeout or an unreachable backend. */
    data object Network : PairingError

    /** Anything else; [message] is for logs, not for the user. */
    data class Unknown(val message: String?) : PairingError
}
