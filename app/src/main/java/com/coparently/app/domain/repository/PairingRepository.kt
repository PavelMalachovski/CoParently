package com.coparently.app.domain.repository

import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import kotlinx.coroutines.flow.Flow

/**
 * Owns the co-parent link: whether it exists, how to offer one, and how to end it.
 *
 * Failures come back as `Result.failure(PairingException)` carrying a
 * `PairingError`, so callers map one typed value to one message.
 */
interface PairingRepository {

    /**
     * Emits the current pairing state and every subsequent change, driven by
     * Firestore snapshot listeners — the inviting phone learns that its
     * invitation was accepted without polling or a push. Recovers to
     * [PairingState.Loading] rather than terminating if a transient Firestore
     * error occurs (offline with no cache, a rules mismatch); it also emits
     * [PairingState.Loading] whenever there is no signed-in user, including
     * briefly on a cold start before Firebase Auth restores its session.
     */
    fun observePairingState(): Flow<PairingState>

    /**
     * Returns this user's outstanding code/QR/link invite, creating one only
     * when there is no pending, unexpired one already. Reuse matters: a code
     * the user has already sent by message must not silently stop working.
     */
    suspend fun createOrReuseInviteCode(): Result<PairingInvite>

    /**
     * Withdraws only this user's active code/QR/link invite (the one
     * [createOrReuseInviteCode] returns) so that code stops working. Pending
     * email invitations sent via [sendEmailInvitation] are a separate,
     * longer-lived offer and are left untouched.
     */
    suspend fun revokeActiveInvite(): Result<Unit>

    /**
     * Creates an invitation addressed to [email]. Fails with a
     * [com.coparently.app.data.remote.firebase.PairingException] wrapping
     * [PairingError.Unknown] without writing anything if [email] is blank or
     * not a plausible email address.
     */
    suspend fun sendEmailInvitation(email: String): Result<Unit>

    /** Redeems an invitation by its short [code]. */
    suspend fun redeem(code: String): Result<Unit>

    /** Accepts an invitation addressed to this user by document id. */
    suspend fun acceptIncoming(invitationId: String): Result<Unit>

    /** Declines an invitation addressed to this user. */
    suspend fun rejectIncoming(invitationId: String): Result<Unit>

    /** Ends the co-parent link for both parents. Shared data is kept. */
    suspend fun unpair(): Result<Unit>
}
