package com.coparently.app.domain.repository

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
     * invitation was accepted without polling or a push.
     */
    fun observePairingState(): Flow<PairingState>

    /**
     * Returns this user's outstanding invite, creating one only when there is
     * no pending, unexpired invite already. Reuse matters: a code the user has
     * already sent by message must not silently stop working.
     */
    suspend fun createOrReuseInviteCode(): Result<PairingInvite>

    /** Withdraws the active invite so its code stops working. */
    suspend fun revokeActiveInvite(): Result<Unit>

    /** Creates an invitation addressed to [email]. */
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
