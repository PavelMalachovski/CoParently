package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.model.PairingError
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    private lateinit var functions: FirebaseFunctions
    private lateinit var callableRef: HttpsCallableReference
    private lateinit var pairingFunctions: PairingFunctions

    @Before
    fun setUp() {
        functions = mockk()
        callableRef = mockk()
        pairingFunctions = PairingFunctions(functions)
        every { functions.getHttpsCallable("acceptPairingInvitation") } returns callableRef
    }

    // ---- acceptInvitation response parsing ---------------------------------

    @Test
    fun `acceptInvitation carries the role through when the callable reports one`() = runTest {
        stubResponse(mapOf("partnerId" to "user-b", "role" to "dad"))

        val outcome = pairingFunctions.acceptInvitation(invitationId = "invite-1")

        assertEquals(AcceptInvitationResult(partnerId = "user-b", role = "dad"), outcome.getOrNull())
    }

    @Test
    fun `acceptInvitation succeeds with a null role when the callable does not report one`() = runTest {
        // A staged rollout can pair a client against a backend (or the reverse) that predates
        // the role field. Pairing already succeeded server-side by the time this is parsed;
        // only the local migration hint is missing, so this must not fail the whole accept —
        // that would report a successful pairing as failed, with no way to retry it (the
        // invitation is no longer pending once accepted).
        stubResponse(mapOf("partnerId" to "user-b"))

        val outcome = pairingFunctions.acceptInvitation(invitationId = "invite-1")

        assertEquals(AcceptInvitationResult(partnerId = "user-b", role = null), outcome.getOrNull())
    }

    @Test
    fun `acceptInvitation still fails when partnerId is missing`() = runTest {
        // Unlike role, partnerId is required: a response without it is a genuine contract
        // violation with the callable, not a version-skew case to tolerate.
        stubResponse(mapOf("role" to "dad"))

        val outcome = pairingFunctions.acceptInvitation(invitationId = "invite-1")

        assertTrue(outcome.isFailure)
    }

    private fun stubResponse(data: Map<String, Any?>) {
        val result = mockk<HttpsCallableResult>()
        every { result.getData() } returns data
        every { callableRef.call(any()) } returns Tasks.forResult(result)
    }

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
