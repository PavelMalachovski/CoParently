package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.remote.firebase.AcceptCalendarFriendResult
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.friends.CalendarFriendGrant
import com.coparently.app.domain.friends.CalendarFriendPolicy
import com.coparently.app.domain.friends.FriendProfile
import com.coparently.app.domain.guests.GuestInvite
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.repository.FriendRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-backed [FriendRepository].
 *
 * Writes the invitation document; the redemption that follows is a Cloud Function, for the same
 * reason pairing's and the guest's are — it must read the inviter's `users` document to prove
 * they are a paired parent, which the caller cannot read until the grant exists.
 *
 * Expiry is applied **on read** through [CalendarFriendPolicy] rather than trusted from storage,
 * so a lapsed grant disappears from the parents' list the moment it lapses, with no sweep in the
 * path. The security rule enforces the same instant server-side, so this is presentation, not the
 * gate.
 */
@Singleton
class FriendRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authService: FirebaseAuthService,
    private val pairingFunctions: PairingFunctions
) : FriendRepository {

    override suspend fun inviteFriend(grantExpiresAtMillis: Long): Result<GuestInvite> {
        // Refused here as well as by the create rule and the callable, for the reason
        // `GuestRepositoryImpl` states: the rule stops a malformed document existing, the
        // callable stops it meaning anything, and this stops the user being shown a code that
        // was never going to work.
        if (grantExpiresAtMillis <= System.currentTimeMillis()) {
            return Result.failure(PairingException(PairingError.GrantEnded))
        }
        return runFriend {
            val user = authService.getCurrentUser()
                ?: throw PairingException(PairingError.Unknown("Not signed in"))
            val profile = firestore.collection(USERS).document(user.uid).get().await()
            val invite = GuestInvite(
                id = UUID.randomUUID().toString(),
                code = InviteCodeGenerator.generate(),
                // Empty: a friend is invited to the calendar, not to one child.
                childInfoId = "",
                inviteExpiresAtMillis = System.currentTimeMillis() + INVITE_TTL_MILLIS,
                grantExpiresAtMillis = grantExpiresAtMillis
            )
            firestore.collection(INVITATIONS).document(invite.id).set(
                mapOf(
                    "id" to invite.id,
                    "code" to invite.code,
                    "fromUserId" to user.uid,
                    "fromUserName" to (profile.getString("name") ?: user.email.orEmpty()),
                    "fromUserEmail" to user.email.orEmpty(),
                    // Empty, like the guest invite's: a parent generally does not know which
                    // account their child's grandmother signs in with.
                    "toEmail" to "",
                    "status" to STATUS_PENDING,
                    "createdAt" to System.currentTimeMillis(),
                    "expiresAt" to invite.inviteExpiresAtMillis,
                    "acceptedBy" to null,
                    "kind" to KIND_FRIEND,
                    "friendExpiresAt" to invite.grantExpiresAtMillis
                )
            ).await()
            invite
        }
    }

    override suspend fun acceptFriendInvite(code: String): Result<AcceptCalendarFriendResult> {
        val normalized = code.trim().uppercase()
        if (!InviteCodeGenerator.isValid(normalized)) {
            return Result.failure(PairingException(PairingError.NotFound))
        }
        return pairingFunctions.acceptCalendarFriendInvitation(code = normalized)
    }

    override fun observeFamilyFriends(): Flow<List<CalendarFriendGrant>> {
        val myUid = authService.getCurrentUser()?.uid ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = firestore.collection(CALENDAR_FRIENDS)
                .whereArrayContains("familyParents", myUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // Not closed: a denied or dropped listener must not take the screen's
                        // whole flow down, and an empty list is the honest reading of "this
                        // device cannot see any friends right now".
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val grants = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        FriendMappers.grantFrom(doc.id, doc.data)
                    }
                    trySend(CalendarFriendPolicy.active(grants, System.currentTimeMillis()))
                }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun revokeFriend(friendUid: String): Result<Unit> = runFriend {
        firestore.collection(CALENDAR_FRIENDS).document(friendUid).delete().await()
    }

    override fun observeMyGrant(): Flow<CalendarFriendGrant?> {
        val myUid = authService.getCurrentUser()?.uid ?: return flowOf(null)
        return observeDocument(CALENDAR_FRIENDS, myUid)
            .map { data ->
                FriendMappers.grantFrom(myUid, data)
                    ?.takeIf { CalendarFriendPolicy.isActive(it, System.currentTimeMillis()) }
            }
    }

    override suspend fun myGrant(): CalendarFriendGrant? {
        val myUid = authService.getCurrentUser()?.uid ?: return null
        val data = try {
            firestore.collection(CALENDAR_FRIENDS).document(myUid).get().await().data
        } catch (e: CancellationException) {
            // Never swallowed: `runFriend` in this same file rethrows it for the same reason —
            // a cancelled coroutine that reports itself as a failed read is a lie about why.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Could not read this account's calendar-friend grant", e)
            return null
        }
        return FriendMappers.grantFrom(myUid, data)
            ?.takeIf { CalendarFriendPolicy.isActive(it, System.currentTimeMillis()) }
    }

    override fun observeMyProfile(): Flow<FriendProfile?> {
        val myUid = authService.getCurrentUser()?.uid ?: return flowOf(null)
        return observeFriendProfile(myUid)
    }

    override suspend fun saveMyProfile(profile: FriendProfile): Result<Unit> = runFriend {
        val user = authService.getCurrentUser()
            ?: throw PairingException(PairingError.Unknown("Not signed in"))
        // The Google account's own picture, when the profile carries none. The rule
        // `ProfileIdentity` applies to a parent's avatar, applied here: take the strongest
        // source that actually has a value, and never let it overwrite something already
        // stored — a friend who has set a picture of their own keeps it.
        val photoUrl = profile.photoUrl?.takeIf { it.isNotBlank() }
            ?: user.photoUrl?.toString()?.takeIf { it.isNotBlank() }
        // The document id is always this account's own uid: the rule refuses any other, and
        // writing one would be an attempt to author somebody else's profile.
        firestore.collection(FRIEND_PROFILES).document(user.uid)
            .set(FriendMappers.profileToMap(profile.copy(uid = user.uid, photoUrl = photoUrl)))
            .await()
    }

    override fun observeFriendProfile(friendUid: String): Flow<FriendProfile?> =
        observeDocument(FRIEND_PROFILES, friendUid)
            .map { data -> FriendMappers.profileFrom(friendUid, data) }

    /** One document as a flow, degrading a failed listener to null rather than closing. */
    private fun observeDocument(collection: String, id: String): Flow<Map<String, Any?>?> =
        callbackFlow {
            val registration = firestore.collection(collection).document(id)
                .addSnapshotListener { snapshot, error ->
                    trySend(if (error != null) null else snapshot?.data)
                }
            awaitClose { registration.remove() }
        }

    /**
     * Runs a Firestore block and normalizes any failure into a [PairingException], the shape
     * `GuestRepositoryImpl.runGuest` uses. Cancellation propagates rather than being reported as
     * a failed invitation.
     */
    private suspend fun <T> runFriend(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: PairingException) {
        Result.failure(e)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(PairingException(PairingError.Unknown(e.message)))
    }

    private companion object {
        const val TAG = "FriendRepository"
        const val USERS = "users"
        const val INVITATIONS = "invitations"
        const val CALENDAR_FRIENDS = "calendar_friends"
        const val FRIEND_PROFILES = "friend_profiles"
        const val STATUS_PENDING = "pending"

        /** Marks the document as a friend invitation; matches `FRIEND_INVITATION` server-side. */
        const val KIND_FRIEND = "friend"

        /**
         * How long the *offer* stays redeemable — a week, matching the guest invite's and for the
         * same reason: it is read out to somebody who will install the app that evening.
         *
         * Not how long the access lasts: that is `grantExpiresAtMillis`, chosen by the parent and
         * stamped verbatim at redemption rather than restarted.
         */
        const val INVITE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
