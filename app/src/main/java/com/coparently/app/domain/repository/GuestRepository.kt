package com.coparently.app.domain.repository

import com.coparently.app.data.remote.firebase.AcceptGuestResult
import com.coparently.app.domain.guests.GuestInvite

/**
 * Letting somebody read one child's record without making them a parent.
 *
 * Separate from [PairingRepository] on purpose, mirroring the two separate callables behind
 * it: a guest and a co-parent are different things, and the code that creates one must not be
 * one edit away from creating the other. Failures come back as `Result.failure` carrying a
 * `PairingException`, the same way pairing's do, so a caller maps one typed value to one
 * message.
 */
interface GuestRepository {

    /**
     * Offers guest access to [childInfoId], ending at [grantExpiresAtMillis].
     *
     * Always mints a **fresh** invitation rather than reusing a pending one, unlike
     * [PairingRepository.createOrReuseInviteCode]. A co-parent code identifies the one link
     * this account can have, so reusing it is what stops a code already sent by message from
     * dying. A guest code identifies one *person* being let in: two grandparents need two
     * codes, and a code that has been read out to one of them must not silently become the
     * other's.
     *
     * @param childInfoId The one child record the guest will be able to read.
     * @param grantExpiresAtMillis When the access ends, epoch millis. Stamped on the grant
     *   verbatim at redemption, so a guest who redeems late gets less time rather than a
     *   fresh window. Must be in the future — the create rule and the callable both refuse
     *   anything else, because the one default this feature must never have is "forever".
     */
    suspend fun inviteGuest(childInfoId: String, grantExpiresAtMillis: Long): Result<GuestInvite>

    /**
     * Redeems a guest invitation by its short [code].
     *
     * Reaches `acceptGuestInvitation`, which never writes `partnerId` and never assigns a
     * parent slot. A co-parent code offered here fails with
     * [com.coparently.app.domain.model.PairingError.NotGuestInvitation] rather than pairing
     * the two accounts.
     */
    suspend fun acceptGuestInvite(code: String): Result<AcceptGuestResult>
}
