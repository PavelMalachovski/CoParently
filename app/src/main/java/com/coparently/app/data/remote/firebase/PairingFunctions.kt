package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.model.PairingError
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
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
     * @return the co-parent's Firebase UID on success.
     */
    suspend fun acceptInvitation(
        code: String? = null,
        invitationId: String? = null
    ): Result<String> {
        val payload = buildMap<String, Any> {
            code?.let { put("code", it) }
            invitationId?.let { put("invitationId", it) }
        }
        return call("acceptPairingInvitation", payload) { it["partnerId"] as? String ?: "" }
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
    } catch (
        // Any backend failure becomes a typed PairingError; the caller decides
        // how to surface it. Rethrowing would only crash the UI layer.
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(PairingException(toPairingError(e)))
    }

    companion object {

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

/** Carries a [PairingError] through `Result.failure`. */
class PairingException(val error: PairingError) : Exception(error.toString())
