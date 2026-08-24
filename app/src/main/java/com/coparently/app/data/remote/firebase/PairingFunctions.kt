package com.coparently.app.data.remote.firebase

import android.util.Log
import com.coparently.app.domain.model.PairingError
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin client for the invitation Cloud Functions.
 *
 * Translates [FirebaseFunctionsException] into a [PairingError] so the layers
 * above never inspect exception text or Firebase error codes.
 *
 * Guest redemption lives here too, next to co-parent redemption, even though the two must
 * never be confused server-side. The thing they share is exactly this class's job — one
 * `call` helper, one reason-to-[PairingError] table — and a second copy of that table is a
 * second place for the two to drift apart. What must not be shared is the *decision*, and
 * that is made by two separate callables in `functions/index.js`, each of which refuses the
 * other's `kind`.
 */
@Singleton
class PairingFunctions @Inject constructor(
    private val functions: FirebaseFunctions
) {

    /**
     * Redeems an invitation by [code] or by [invitationId] — exactly one.
     *
     * @return the co-parent's Firebase UID and, if the deployed callable reports one, this
     *   device's newly assigned parent slot (see `assignSlots` in `functions/index.js`) — or a
     *   [PairingException]-wrapped [PairingError] on failure. `partnerId` is required: a
     *   response missing it is a contract violation with the callable and is treated as
     *   [PairingError.Unknown], not silently coerced to a blank UID. `role` is soft-parsed
     *   instead: a client built against a redeployed backend that has not shipped yet (or a
     *   backend that has not been redeployed for a client that has) must not report a pairing
     *   that succeeded server-side as a failed accept — see [AcceptInvitationResult.role].
     * @throws IllegalArgumentException if both or neither of [code] and [invitationId] are
     *   given — this is a caller programming error, not a backend failure, so it is not
     *   folded into the returned [Result].
     */
    suspend fun acceptInvitation(
        code: String? = null,
        invitationId: String? = null
    ): Result<AcceptInvitationResult> {
        require((code == null) != (invitationId == null)) {
            "acceptInvitation requires exactly one of code or invitationId, got " +
                "code=$code, invitationId=$invitationId"
        }
        val payload = buildMap<String, Any> {
            code?.let { put("code", it) }
            invitationId?.let { put("invitationId", it) }
        }
        return call("acceptPairingInvitation", payload) { data ->
            val partnerId = data["partnerId"] as? String
            val role = (data["role"] as? String)?.takeIf { it.isNotBlank() }
            if (role == null) {
                Log.w(TAG, "acceptPairingInvitation succeeded but returned no role; slot re-stamp will be skipped")
            }
            AcceptInvitationResult(
                partnerId = checkNotNull(partnerId?.takeIf { it.isNotBlank() }) {
                    "acceptPairingInvitation succeeded but returned no partnerId"
                },
                role = role
            )
        }
    }

    /**
     * Redeems a **guest** invitation by [code] or by [invitationId] — exactly one.
     *
     * Reaches `acceptGuestInvitation`, never `acceptPairingInvitation`. The two callables
     * refuse each other's `kind`, so a code offered to the wrong one comes back as
     * [PairingError.NotGuestInvitation] or [PairingError.GuestInvitation] rather than
     * quietly doing the other thing.
     *
     * @return the child record the caller may now read and when that ends, or a
     *   [PairingException]-wrapped [PairingError] on failure. Both fields are required: a
     *   response missing either is a contract violation, and a grant with no end is the one
     *   outcome this feature must never produce by accident.
     * @throws IllegalArgumentException if both or neither of [code] and [invitationId] are
     *   given — a caller programming error, not a backend failure.
     */
    suspend fun acceptGuestInvitation(
        code: String? = null,
        invitationId: String? = null
    ): Result<AcceptGuestResult> {
        require((code == null) != (invitationId == null)) {
            "acceptGuestInvitation requires exactly one of code or invitationId, got " +
                "code=$code, invitationId=$invitationId"
        }
        val payload = buildMap<String, Any> {
            code?.let { put("code", it) }
            invitationId?.let { put("invitationId", it) }
        }
        return call("acceptGuestInvitation", payload) { data ->
            val childInfoId = data["childInfoId"] as? String
            val expiresAtMillis = (data["expiresAtMillis"] as? Number)?.toLong() ?: 0L
            AcceptGuestResult(
                childInfoId = checkNotNull(childInfoId?.takeIf { it.isNotBlank() }) {
                    "acceptGuestInvitation succeeded but returned no childInfoId"
                },
                expiresAtMillis = expiresAtMillis.also {
                    check(it > 0L) { "acceptGuestInvitation succeeded but returned no expiry" }
                }
            )
        }
    }

    /**
     * Redeems a **calendar friend** invitation by [code] or by [invitationId] — exactly one.
     *
     * Reaches `acceptCalendarFriendInvitation`, the third callable beside pairing and guest.
     * The pairing one refuses this `kind` outright, so a friend code offered there can never
     * run `assignSlots` and hand a friend a parent slot.
     *
     * @return the two parents whose calendar the caller may now read and when that ends, or a
     *   [PairingException]-wrapped [PairingError] on failure. Both are required: a pair of the
     *   wrong size or a grant with no end is a contract violation, not something to paper over.
     * @throws IllegalArgumentException if both or neither of [code] and [invitationId] are
     *   given — a caller programming error, not a backend failure.
     */
    suspend fun acceptCalendarFriendInvitation(
        code: String? = null,
        invitationId: String? = null
    ): Result<AcceptCalendarFriendResult> {
        require((code == null) != (invitationId == null)) {
            "acceptCalendarFriendInvitation requires exactly one of code or invitationId, got " +
                "code=$code, invitationId=$invitationId"
        }
        val payload = buildMap<String, Any> {
            code?.let { put("code", it) }
            invitationId?.let { put("invitationId", it) }
        }
        return call("acceptCalendarFriendInvitation", payload) { data ->
            @Suppress("UNCHECKED_CAST")
            val parents = (data["familyParents"] as? List<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()
            val expiresAtMillis = (data["expiresAtMillis"] as? Number)?.toLong() ?: 0L
            AcceptCalendarFriendResult(
                familyParents = parents.also {
                    check(it.size == 2) {
                        "acceptCalendarFriendInvitation succeeded but named ${it.size} parents"
                    }
                },
                expiresAtMillis = expiresAtMillis.also {
                    check(it > 0L) {
                        "acceptCalendarFriendInvitation succeeded but returned no expiry"
                    }
                }
            )
        }
    }

    /**
     * Removes the co-parent link.
     *
     * @return the former partner's UID, or null when there was no link.
     */
    suspend fun unpair(): Result<String?> =
        call("unpairCoParent", emptyMap()) { it["unpairedFrom"] as? String }

    private suspend fun <T> call(
        name: String,
        payload: Map<String, Any>,
        parse: (Map<*, *>) -> T
    ): Result<T> = try {
        val result = functions.getHttpsCallable(name).call(payload).await()
        Result.success(parse((result.getData() as? Map<*, *>) ?: emptyMap<String, Any>()))
    } catch (e: CancellationException) {
        // Coroutine cancellation must propagate, not be reported as a pairing failure —
        // otherwise navigating away mid-call surfaces a spurious error instead of the
        // silent cancellation structured concurrency expects.
        throw e
    } catch (
        // Any remaining backend or parsing failure becomes a typed PairingError; the
        // caller decides how to surface it. Rethrowing would only crash the UI layer.
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(PairingException(toPairingError(e), e))
    }

    companion object {
        private const val TAG = "PairingFunctions"

        /** Maps a callable failure to the matching [PairingError]. */
        fun toPairingError(e: Throwable): PairingError {
            val reason = ((e as? FirebaseFunctionsException)?.details as? Map<*, *>)
                ?.get("reason") as? String
            return when (reason) {
                "not-found" -> PairingError.NotFound
                "invitation-expired" -> PairingError.Expired
                "invitation-not-pending" -> PairingError.NotPending
                "self-pairing" -> PairingError.SelfPairing
                "already-paired" -> PairingError.AlreadyPaired
                "wrong-recipient" -> PairingError.WrongRecipient
                "guest-invitation" -> PairingError.GuestInvitation
                "not-a-guest-invitation" -> PairingError.NotGuestInvitation
                "friend-invitation" -> PairingError.FriendInvitation
                "not-a-friend-invitation" -> PairingError.NotFriendInvitation
                "grant-expired" -> PairingError.GrantEnded
                "inviter-not-entitled" -> PairingError.InviterNotEntitled
                "already-entitled" -> PairingError.AlreadyEntitled
                else -> when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.UNAVAILABLE,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> PairingError.Network
                    FirebaseFunctionsException.Code.NOT_FOUND -> PairingError.NotFound
                    else -> PairingError.Unknown(e.message)
                }
            }
        }
    }
}

/** Carries a [PairingError] through `Result.failure`, keeping the original [cause] for logging. */
class PairingException(val error: PairingError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

/**
 * What [PairingFunctions.acceptInvitation] returns on success.
 *
 * @property partnerId The co-parent's Firebase UID.
 * @property role The parent slot ("mom" or "dad") this device was just assigned, or null if
 *   the callable did not report one. The inviter keeps whatever slot it already had; the
 *   accepter always gets the other one — see `assignSlots` in `functions/index.js`. Callers
 *   compare this to the slot they held before accepting to detect a change and re-stamp
 *   accordingly. Nullable rather than required: unlike [partnerId], a missing `role` is not
 *   treated as a contract violation, because it is reachable in the wild during a staged
 *   rollout — a client built against a redeployed backend running ahead of it, or the reverse
 *   — and in both cases the pairing itself has already succeeded server-side by the time this
 *   is parsed. Failing the whole accept over a field that only gates a best-effort local
 *   migration would report a successful pairing as failed, with no way to retry it (the
 *   invitation is no longer pending). A null here means only that the migration is skipped,
 *   not that pairing failed.
 */
data class AcceptInvitationResult(val partnerId: String, val role: String?)

/**
 * What [PairingFunctions.acceptGuestInvitation] returns on success.
 *
 * @property childInfoId The one child record the caller may now read. Exactly one: a guest is
 *   invited to a child, not to a family.
 * @property expiresAtMillis When that access ends, as epoch milliseconds. Required, unlike
 *   [AcceptInvitationResult.role]: a missing `role` costs a best-effort local migration,
 *   while a missing expiry would be shown to the guest as access that never ends.
 */
data class AcceptGuestResult(val childInfoId: String, val expiresAtMillis: Long)

/**
 * What `acceptCalendarFriendInvitation` returns: the pair whose calendar the caller may now
 * read, and the instant that access ends.
 *
 * @property familyParents Exactly two UIDs — the friend queries events by them.
 * @property expiresAtMillis Epoch millis, always positive; see [CalendarFriendPolicy].
 */
data class AcceptCalendarFriendResult(
    val familyParents: List<String>,
    val expiresAtMillis: Long
)
