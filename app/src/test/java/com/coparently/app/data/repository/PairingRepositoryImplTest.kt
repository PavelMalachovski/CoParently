package com.coparently.app.data.repository

import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.repository.MessageRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingRepositoryImplTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var authService: FirebaseAuthService
    private lateinit var pairingFunctions: PairingFunctions
    private lateinit var messageRepository: MessageRepository
    private lateinit var repository: PairingRepositoryImpl

    private lateinit var invitationsCollection: CollectionReference
    private lateinit var invitationsQuery: Query

    @Before
    fun setUp() {
        firestore = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        pairingFunctions = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "user-a"
        every { firebaseUser.email } returns "a@example.com"
        every { authService.getCurrentUser() } returns firebaseUser

        // Any users/{id} read (partner name lookups) resolves immediately to an empty
        // snapshot. Without this, an un-stubbed relaxed-mockk Task never completes its
        // addOnCompleteListener, and `.await()` on it hangs for real wall-clock time
        // instead of failing fast — that is what made the first version of this test
        // class hang for a full minute on "redeem normalizes the code...".
        val usersCollection = mockk<CollectionReference>(relaxed = true)
        val userDocument = mockk<DocumentReference>(relaxed = true)
        val userSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        every { firestore.collection("users") } returns usersCollection
        every { usersCollection.document(any()) } returns userDocument
        every { userDocument.get() } returns Tasks.forResult(userSnapshot)

        // No locally cached conversation with the partner yet, by default.
        every { messageRepository.getConversations(any()) } returns flowOf(emptyList())

        invitationsCollection = mockk(relaxed = true)
        invitationsQuery = mockk(relaxed = true)
        every { firestore.collection("invitations") } returns invitationsCollection
        every { invitationsCollection.whereEqualTo("fromUserId", any<String>()) } returns invitationsQuery
        every { invitationsQuery.whereEqualTo("status", any<String>()) } returns invitationsQuery

        repository = PairingRepositoryImpl(
            firestore = firestore,
            authService = authService,
            pairingFunctions = pairingFunctions,
            messageRepository = messageRepository
        )
    }

    // ---- redeem ---------------------------------------------------------

    @Test
    fun `redeem rejects a malformed code without calling the backend`() = runTest {
        val result = repository.redeem("nope")

        assertTrue(result.isFailure)
        assertEquals(
            PairingError.NotFound,
            (result.exceptionOrNull() as PairingException).error
        )
        coVerify(exactly = 0) { pairingFunctions.acceptInvitation(any(), any()) }
    }

    @Test
    fun `redeem normalizes the code before calling the backend`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.success("user-b")

        val result = repository.redeem("  4f7k2m ")

        assertTrue(result.isSuccess)
        coVerify { pairingFunctions.acceptInvitation(code = "4F7K2M", invitationId = null) }
    }

    @Test
    fun `redeem surfaces the backend error unchanged`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.failure(PairingException(PairingError.AlreadyPaired))

        val result = repository.redeem("4F7K2M")

        assertEquals(
            PairingError.AlreadyPaired,
            (result.exceptionOrNull() as PairingException).error
        )
    }

    // ---- unpair -----------------------------------------------------------

    @Test
    fun `unpair delegates to the callable`() = runTest {
        coEvery { pairingFunctions.unpair() } returns Result.success("user-b")

        val result = repository.unpair()

        assertTrue(result.isSuccess)
        coVerify { pairingFunctions.unpair() }
    }

    @Test
    fun `unpair treats a null unpairedFrom as success`() = runTest {
        coEvery { pairingFunctions.unpair() } returns Result.success(null)

        val result = repository.unpair()

        assertTrue(result.isSuccess)
    }

    // ---- createOrReuseInviteCode -------------------------------------------

    @Test
    fun `createOrReuseInviteCode reuses an existing unexpired code invite without writing`() = runTest {
        val existing = pendingInviteDoc(
            code = "ABCDEF",
            toEmail = "",
            expiresAt = System.currentTimeMillis() + HOUR_MILLIS,
            createdAt = System.currentTimeMillis()
        )
        stubOwnInvitesQuery(existing)

        val result = repository.createOrReuseInviteCode()

        assertTrue(result.isSuccess)
        assertEquals("ABCDEF", result.getOrNull()?.code)
        verify(exactly = 0) { invitationsCollection.document(any()) }
    }

    @Test
    fun `createOrReuseInviteCode writes a new invite when the existing one is expired`() = runTest {
        val expired = pendingInviteDoc(
            code = "OLDCOD",
            toEmail = "",
            expiresAt = System.currentTimeMillis() - HOUR_MILLIS,
            createdAt = System.currentTimeMillis() - HOUR_MILLIS
        )
        stubOwnInvitesQuery(expired)
        val newInviteRef = mockk<DocumentReference>(relaxed = true)
        every { invitationsCollection.document(any()) } returns newInviteRef
        every { newInviteRef.set(any()) } returns Tasks.forResult<Void>(null)

        val result = repository.createOrReuseInviteCode()

        assertTrue(result.isSuccess)
        assertNotEquals("OLDCOD", result.getOrNull()?.code)
        verify { newInviteRef.set(any()) }
    }

    @Test
    fun `createOrReuseInviteCode ignores an email invitation and picks the newest unexpired code invite`() =
        runTest {
            val emailInvite = pendingInviteDoc(
                code = "EMAIL1",
                toEmail = "other@example.com",
                expiresAt = System.currentTimeMillis() + HOUR_MILLIS,
                createdAt = System.currentTimeMillis() + 1
            )
            val expiredCodeInvite = pendingInviteDoc(
                code = "EXPIRD",
                toEmail = "",
                expiresAt = System.currentTimeMillis() - HOUR_MILLIS,
                createdAt = System.currentTimeMillis() - HOUR_MILLIS
            )
            val olderCodeInvite = pendingInviteDoc(
                code = "OLDER1",
                toEmail = "",
                expiresAt = System.currentTimeMillis() + HOUR_MILLIS,
                createdAt = System.currentTimeMillis() - 1_000
            )
            val newestCodeInvite = pendingInviteDoc(
                code = "NEWEST",
                toEmail = "",
                expiresAt = System.currentTimeMillis() + HOUR_MILLIS,
                createdAt = System.currentTimeMillis()
            )
            stubOwnInvitesQuery(emailInvite, expiredCodeInvite, olderCodeInvite, newestCodeInvite)

            val result = repository.createOrReuseInviteCode()

            assertTrue(result.isSuccess)
            assertEquals("NEWEST", result.getOrNull()?.code)
            verify(exactly = 0) { invitationsCollection.document(any()) }
        }

    // ---- revokeActiveInvite -------------------------------------------------

    @Test
    fun `revokeActiveInvite cancels only the code invite, not email invitations`() = runTest {
        val codeInviteRef = mockk<DocumentReference>(relaxed = true)
        val codeInvite = pendingInviteDoc(code = "ABCDEF", toEmail = "", reference = codeInviteRef)
        every { codeInviteRef.update("status", "cancelled") } returns Tasks.forResult<Void>(null)

        val emailInviteRef = mockk<DocumentReference>(relaxed = true)
        val emailInvite = pendingInviteDoc(
            code = "EMAIL1",
            toEmail = "other@example.com",
            reference = emailInviteRef
        )
        stubOwnInvitesQuery(codeInvite, emailInvite)

        val result = repository.revokeActiveInvite()

        assertTrue(result.isSuccess)
        verify { codeInviteRef.update("status", "cancelled") }
        verify(exactly = 0) { emailInviteRef.update(any<String>(), any()) }
    }

    // ---- sendEmailInvitation -------------------------------------------------

    @Test
    fun `sendEmailInvitation rejects a malformed address without writing`() = runTest {
        val result = repository.sendEmailInvitation("not-an-email")

        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as PairingException).error is PairingError.Unknown)
        verify(exactly = 0) { firestore.collection("invitations") }
    }

    @Test
    fun `sendEmailInvitation rejects a blank address without writing`() = runTest {
        val result = repository.sendEmailInvitation("   ")

        assertTrue(result.isFailure)
        verify(exactly = 0) { firestore.collection("invitations") }
    }

    // ---- runPairing error normalization -------------------------------------

    @Test
    fun `runPairing normalizes an unexpected Firestore failure to PairingError Unknown`() = runTest {
        val docRef = mockk<DocumentReference>(relaxed = true)
        every { invitationsCollection.document("invite-1") } returns docRef
        every { docRef.update("status", "rejected") } throws
            mockk<FirebaseFirestoreException>(relaxed = true)

        val result = repository.rejectIncoming("invite-1")

        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as PairingException).error is PairingError.Unknown)
    }

    @Test
    fun `runPairing passes a thrown PairingException through unchanged`() = runTest {
        every { authService.getCurrentUser() } returns null

        val result = repository.createOrReuseInviteCode()

        assertTrue(result.isFailure)
        assertEquals(
            PairingError.Unknown("Not signed in"),
            (result.exceptionOrNull() as PairingException).error
        )
    }

    // ---- test helpers ---------------------------------------------------

    private fun stubOwnInvitesQuery(vararg docs: DocumentSnapshot) {
        val snapshot = mockk<QuerySnapshot>(relaxed = true)
        every { snapshot.documents } returns docs.toList()
        every { invitationsQuery.get() } returns Tasks.forResult(snapshot)
    }

    private fun pendingInviteDoc(
        code: String,
        toEmail: String,
        expiresAt: Long = System.currentTimeMillis() + HOUR_MILLIS,
        createdAt: Long = System.currentTimeMillis(),
        reference: DocumentReference = mockk(relaxed = true)
    ): DocumentSnapshot {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.getString("code") } returns code
        every { doc.getString("toEmail") } returns toEmail
        every { doc.getString("fromUserId") } returns "user-a"
        every { doc.getString("fromUserName") } returns "Alice"
        every { doc.getString("fromUserEmail") } returns "a@example.com"
        every { doc.getLong("expiresAt") } returns expiresAt
        every { doc.getLong("createdAt") } returns createdAt
        every { doc.reference } returns reference
        return doc
    }

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
    }
}
