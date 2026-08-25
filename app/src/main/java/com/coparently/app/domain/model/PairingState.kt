package com.coparently.app.domain.model

/**
 * The paired co-parent, as shown on the pairing screen.
 *
 * @property photoUrl The co-parent's avatar, read from their profile document. Null
 *   whenever their phone has not yet run a build that stores one, so the initial-letter
 *   fallback stays load-bearing rather than decorative.
 * @property role The co-parent's slot, `"mom"` or `"dad"`, read from their profile document.
 *   This is the only thing on this device that knows which slot the *other* parent holds —
 *   Room stores a `users` row for the signed-in user alone — so every screen that names a
 *   parent resolves the second name through here. Null when their document carries no slot
 *   yet, which is true of every pair created before slot assignment shipped and stays true
 *   until the backfill runs. Null must not be replaced by "whichever slot is left": with both
 *   parents still on the same slot, that would attribute one parent's days to the other by
 *   name. It is a slot identifier and is never displayed.
 */
data class PartnerSummary(
    val id: String,
    val name: String,
    val email: String,
    val pairedSinceMillis: Long?,
    val photoUrl: String? = null,
    val role: String? = null,
    /**
     * What the co-parent's device says the family co-parents, or empty when their build has
     * never written it. Unioned with this parent's answer so one side saying "children" is
     * enough for the child records to appear on both.
     */
    val caresFor: Set<FamilyKind> = emptySet()
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
