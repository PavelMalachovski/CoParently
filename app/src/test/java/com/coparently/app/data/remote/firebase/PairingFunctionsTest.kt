package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.model.PairingError
import com.google.firebase.functions.FirebaseFunctionsException
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PairingFunctions.toPairingError].
 *
 * This mapping is the literal wire contract with the deployed `acceptPairingInvitation`
 * and `unpairCoParent` callables: each `reason` string in `functions/index.js` must land on
 * its own [PairingError], because that is what decides which of the six actionable messages
 * the pairing screen shows. A rename on either side collapses all six into
 * "something went wrong" with nothing failing, so the strings are pinned here verbatim.
 */
class PairingFunctionsTest {

    @Test
    fun `not-found maps to NotFound`() {
        assertEquals(PairingError.NotFound, mapReason("not-found"))
    }

    @Test
    fun `invitation-expired maps to Expired`() {
        assertEquals(PairingError.Expired, mapReason("invitation-expired"))
    }

    @Test
    fun `invitation-not-pending maps to NotPending`() {
        assertEquals(PairingError.NotPending, mapReason("invitation-not-pending"))
    }

    @Test
    fun `self-pairing maps to SelfPairing`() {
        assertEquals(PairingError.SelfPairing, mapReason("self-pairing"))
    }

    @Test
    fun `already-paired maps to AlreadyPaired`() {
        assertEquals(PairingError.AlreadyPaired, mapReason("already-paired"))
    }

    @Test
    fun `wrong-recipient maps to WrongRecipient`() {
        assertEquals(PairingError.WrongRecipient, mapReason("wrong-recipient"))
    }

    @Test
    fun `the six reason strings map to six distinct errors`() {
        val reasons = listOf(
            "not-found",
            "invitation-expired",
            "invitation-not-pending",
            "self-pairing",
            "already-paired",
            "wrong-recipient"
        )

        assertEquals(reasons.size, reasons.map { mapReason(it) }.toSet().size)
    }

    @Test
    fun `UNAVAILABLE maps to Network`() {
        assertEquals(
            PairingError.Network,
            PairingFunctions.toPairingError(
                functionsException(code = FirebaseFunctionsException.Code.UNAVAILABLE)
            )
        )
    }

    @Test
    fun `DEADLINE_EXCEEDED maps to Network`() {
        assertEquals(
            PairingError.Network,
            PairingFunctions.toPairingError(
                functionsException(code = FirebaseFunctionsException.Code.DEADLINE_EXCEEDED)
            )
        )
    }

    @Test
    fun `a bare NOT_FOUND code without a reason still maps to NotFound`() {
        assertEquals(
            PairingError.NotFound,
            PairingFunctions.toPairingError(
                functionsException(code = FirebaseFunctionsException.Code.NOT_FOUND)
            )
        )
    }

    @Test
    fun `an unrecognized reason falls through to the code mapping`() {
        // Guards the rename hazard from the other direction: a reason string the client
        // does not know must not be silently treated as one it does.
        assertEquals(
            PairingError.Network,
            PairingFunctions.toPairingError(
                functionsException(
                    code = FirebaseFunctionsException.Code.UNAVAILABLE,
                    reason = "some-new-server-reason"
                )
            )
        )
    }

    @Test
    fun `a non-Firebase throwable maps to Unknown`() {
        assertTrue(PairingFunctions.toPairingError(IllegalStateException("boom")) is PairingError.Unknown)
    }

    private fun mapReason(reason: String): PairingError =
        PairingFunctions.toPairingError(
            functionsException(code = FirebaseFunctionsException.Code.INTERNAL, reason = reason)
        )

    private fun functionsException(
        code: FirebaseFunctionsException.Code,
        reason: String? = null
    ): FirebaseFunctionsException {
        val exception = mockk<FirebaseFunctionsException>(relaxed = true)
        every { exception.code } returns code
        every { exception.details } returns reason?.let { mapOf("reason" to it) }
        return exception
    }
}
