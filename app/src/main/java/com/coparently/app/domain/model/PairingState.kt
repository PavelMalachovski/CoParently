package com.coparently.app.domain.model

/** The paired co-parent, as shown on the pairing screen. */
data class PartnerSummary(
    val id: String,
    val name: String,
    val email: String,
    val pairedSinceMillis: Long?
)

/**
 * Whether this account is linked to a co-parent, and what the pairing screen
 * should offer if it is not.
 */
sealed interface PairingState {

    /** The initial state, before the first Firestore snapshot arrives. */
    data object Loading : PairingState

    /**
     * No co-parent linked.
     *
     * @property activeInvite This user's own outstanding invite, if any
     * @property incoming Invitations addressed to this user's email
     */
    data class NotPaired(
        val activeInvite: PairingInvite? = null,
        val incoming: List<PairingInvite> = emptyList()
    ) : PairingState

    /** Linked to [partner]. */
    data class Paired(val partner: PartnerSummary) : PairingState
}
