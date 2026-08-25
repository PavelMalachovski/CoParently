package com.coparently.app.domain.repository

import com.coparently.app.data.remote.firebase.AcceptCalendarFriendResult
import com.coparently.app.domain.friends.CalendarFriendGrant
import com.coparently.app.domain.friends.FriendProfile
import com.coparently.app.domain.guests.GuestInvite
import kotlinx.coroutines.flow.Flow

/**
 * Letting a trusted third person read the family's calendar, and their own profile.
 *
 * Separate from [PairingRepository] and [GuestRepository] for the reason the three callables are
 * separate: a friend, a guest and a co-parent are different things, and the code that admits one
 * must not be one edit away from admitting another. Failures come back as `Result.failure`
 * carrying a `PairingException`, the shape both siblings use.
 */
interface FriendRepository {

    /**
     * Offers calendar access to whoever redeems the returned code, ending at
     * [grantExpiresAtMillis].
     *
     * Mints a **fresh** invitation every time, like [GuestRepository.inviteGuest] and unlike the
     * co-parent code: a friend code identifies one *person* being let in, and a code already read
     * out to one grandparent must not silently become another's.
     *
     * Reuses [GuestInvite] as the carrier — the two invitations differ only in what they open,
     * and the `childInfoId` is empty for a friend, who is invited to the calendar rather than to
     * one child.
     */
    suspend fun inviteFriend(grantExpiresAtMillis: Long): Result<GuestInvite>

    /** Redeems a friend invitation by its short [code]. */
    suspend fun acceptFriendInvite(code: String): Result<AcceptCalendarFriendResult>

    /**
     * The live calendar-friend grants on this family, for the parents' "who can see this" list.
     *
     * Filtered to the grants naming this signed-in parent, so a parent never sees another
     * family's friends; expiry is applied by
     * [com.coparently.app.domain.friends.CalendarFriendPolicy] on read rather than trusted from
     * storage, so a lapsed grant disappears without waiting for a sweep.
     */
    fun observeFamilyFriends(): Flow<List<CalendarFriendGrant>>

    /** Ends [friendUid]'s access. Either parent may revoke. */
    suspend fun revokeFriend(friendUid: String): Result<Unit>

    /**
     * This account's own grant, when the signed-in user is a friend rather than a parent — the
     * flow the friend's own calendar reads to learn whose events to query. Null while they are
     * not a friend of anybody, or once their grant lapses.
     */
    fun observeMyGrant(): Flow<CalendarFriendGrant?>

    /**
     * This account's own grant, read once.
     *
     * The save path's accessor, and it exists for the reason CLAUDE.md's invariant 17 gives:
     * `FriendViewModel.myGrant` is a `WhileSubscribed` StateFlow, and `FriendProfileScreen` is
     * its own route that never collects it — so `myGrant.value` was the initial `null` for every
     * save that ViewModel instance ever made, and the profile went out with an empty
     * `familyParents`, which is the gate the parents read it through.
     *
     * @return the grant, or null when this account is not a calendar friend or it has lapsed.
     */
    suspend fun myGrant(): CalendarFriendGrant?

    /** The friend's own profile, or null before they have written one. */
    fun observeMyProfile(): Flow<FriendProfile?>

    /** Writes the signed-in friend's own profile. Never another account's — the rule refuses it. */
    suspend fun saveMyProfile(profile: FriendProfile): Result<Unit>

    /** A friend's profile as the two parents read it, or null when there is none. */
    fun observeFriendProfile(friendUid: String): Flow<FriendProfile?>
}
