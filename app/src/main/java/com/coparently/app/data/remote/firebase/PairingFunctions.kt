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
 * Thin client for the pairing Cloud Functions.
 *
 * Translates [FirebaseFunctionsException] into a [PairingError] so the layers
 * above never inspect exception text or Firebase error codes.
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
