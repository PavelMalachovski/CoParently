package com.coparently.app.data.repository

import android.content.Context
import app.cash.turbine.test
import com.coparently.app.data.family.SelectedFamilySource
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.AcceptInvitationResult
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.repository.MessageRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingRepositoryImplTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var authService: FirebaseAuthService
    private lateinit var pairingFunctions: PairingFunctions
    private lateinit var messageRepository: MessageRepository
    private lateinit var conversationMigrator: ConversationMigrator
    private lateinit var userDao: UserDao
    private lateinit var context: Context
    private lateinit var selectedFamilySource: SelectedFamilySource
    private lateinit var repository: PairingRepositoryImpl

    private lateinit var usersCollection: CollectionReference

    /** The `users/{id}` snapshot every profile read in this class resolves to. */
    private lateinit var userSnapshot: DocumentSnapshot
    private lateinit var invitationsCollection: CollectionReference
    private lateinit var invitationsQuery: Query

    @Before
    fun setUp() {
        firestore = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        pairingFunctions = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        conversationMigrator = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "user-a"
        every { firebaseUser.email } returns "a@example.com"
        every { authService.getCurrentUser() } returns firebaseUser

        // Any users/{id} read (partner name lookups) resolves immediately to an empty
        // snapshot. Without this, an un-stubbed relaxed-mockk Task never completes its
        // addOnCompleteListener, and `.await()` on it hangs for real wall-clock time
        // instead of failing fast — that is what made the first version of this test
        // class hang for a full minute on "redeem normalizes the code...".
        usersCollection = mockk(relaxed = true)
        val userDocument = mockk<DocumentReference>(relaxed = true)
        userSnapshot = mockk(relaxed = true)
        every { firestore.collection("users") } returns usersCollection
        every { usersCollection.document(any()) } returns userDocument
        every { userDocument.get() } returns Tasks.forResult(userSnapshot)

        // The conversation id is derived from the participant pair, so there is no
        // get-or-create lookup to stub any more — the relaxed mock answers `ensureConversation`.

        invitationsCollection = mockk(relaxed = true)
        invitationsQuery = mockk(relaxed = true)
        every { firestore.collection("invitations") } returns invitationsCollection
        every { invitationsCollection.whereEqualTo("fromUserId", any<String>()) } returns invitationsQuery
        every { invitationsQuery.whereEqualTo("status", any<String>()) } returns invitationsQuery

        userDao = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getString(any()) } returns "Co-parent"
        // Relaxed: reconciling the shown family needs Firebase Auth and a real row, and what
        // these tests pin is that the mirror *delegates* the decision rather than making it.
        selectedFamilySource = mockk(relaxed = true)

        repository = PairingRepositoryImpl(
            firestore = firestore,
            authService = authService,
            pairingFunctions = pairingFunctions,
            postPairingConversationSetup = PostPairingConversationSetup(messageRepository, conversationMigrator),
            userDao = userDao,
            selectedFamilySource = selectedFamilySource,
            context = context
        )
    }

    // ---- observePairingState --------------------------------------------

    @Test
    fun `observePairingState emits Loading then NotPaired then Paired as the link is made`() = runTest {
        val listeners = stubRealtimeListeners()

        repository.observePairingState().test {
            assertEquals(PairingState.Loading, awaitItem())
            runCurrent()

            listeners.emitInvites()
            listeners.emitUser(userDoc(partnerId = ""))
            assertEquals(PairingState.NotPaired(activeInvite = null, incoming = emptyList()), awaitItem())

            listeners.emitUser(userDoc(partnerId = "u2", pairedAt = 123L))
            assertEquals(
                PairingState.Paired(
                    PartnerSummary(id = "u2", name = "", email = "", pairedSinceMillis = 123L)
                ),
                awaitItem()
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the paired partner carries the avatar stored in their profile`() = runTest {
        // Same field, same path as this user's own avatar — the co-parent's card would look
        // accidental showing an initial next to a photo of you. It stays null until their
        // phone runs a build whose `ensureProfile` writes one, so the card keeps its
        // initial-letter fallback for as long as the other device is behind.
        every { userSnapshot.getString("name") } returns "Bob Dvorak"
        every { userSnapshot.getString("email") } returns "bob@example.com"
        every { userSnapshot.getString("profilePhotoUrl") } returns PARTNER_PHOTO
        val listeners = stubRealtimeListeners()

        repository.observePairingState().test {
            assertEquals(PairingState.Loading, awaitItem())
            runCurrent()
            listeners.emitInvites()
            listeners.emitUser(userDoc(partnerId = "u2", pairedAt = 123L))

            assertEquals(
                PairingState.Paired(
                    PartnerSummary(
                        id = "u2",
                        name = "Bob Dvorak",
                        email = "bob@example.com",
                        pairedSinceMillis = 123L,
                        photoUrl = PARTNER_PHOTO
                    )
                ),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observePairingState mirrors the Paired transition into Room`() = runTest {
        // Everything outside the pairing screen (chat, expenses, budgets, event sync) reads
        // the link from Room, so the observed transition — not the redeem() call, which only
        // ever runs on the accepting device — is what has to write it.
        //
        // What it writes is the **list**. `partnerId` stopped meaning "my co-parent" and
        // started meaning "the family this device is showing", and only `SelectedFamilySource`
        // writes that — mirroring the observed partner onto it here would drag a parent
        // looking at one family into another the moment the other one's state re-emitted.
        coEvery { userDao.getUserById("user-a") } returns userEntity(partnerId = null)
        val listeners = stubRealtimeListeners()

        repository.observePairingState().test {
            assertEquals(PairingState.Loading, awaitItem())
            runCurrent()
            listeners.emitInvites()
            listeners.emitUser(userDoc(partnerId = "u2", pairedAt = 123L))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            userDao.updateUser(userEntity(partnerId = null).copy(partnerIdsJson = "[\"u2\"]"))
        }
        // The legacy-conversation merge runs immediately after the canonical conversation is
        // ensured, inside the same guarded block, so a merge failure can never surface as a
        // failed pairing.
        coVerify { messageRepository.ensureConversation("user-a", "u2", any()) }
        coVerify { conversationMigrator.mergeLegacyConversations("user-a", "u2") }
    }

    @Test
    fun `observePairingState hands an ended link to the selected-family source`() = runTest {
        // This used to assert that the mirror cleared `partnerId` directly. It no longer may:
        // that field is now "the family this device is showing", and with two co-parents an
        // ending relationship might not be the one on screen — clearing it here would blank a
        // family the parent is still in. `SelectedFamilySource.reconcile()` is what decides,
        // because it is the only thing that knows both the full list and the choice.
        coEvery { userDao.getUserById("user-a") } returns userEntity(partnerId = "u2")
        val listeners = stubRealtimeListeners()

        repository.observePairingState().test {
            assertEquals(PairingState.Loading, awaitItem())
            runCurrent()
            listeners.emitInvites()
            listeners.emitUser(userDoc(partnerId = ""))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { selectedFamilySource.reconcile() }
    }

    @Test
    fun `observePairingState stays Loading when the user-document listener errors`() = runTest {
        // A failed read must never be reported as a fact about the link: forwarding the null
        // snapshot would read downstream as partnerId == "" and flip a paired account to
        // NotPaired, and a half-read snapshot would produce a Paired carrying a blank partner
        // as if it were real.
        val listeners = stubRealtimeListeners()

        repository.observePairingState().test {
            assertEquals(PairingState.Loading, awaitItem())
            runCurrent()
            listeners.emitInvites()
            listeners.failUser(mockk<FirebaseFirestoreException>(relaxed = true))

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { userDao.updateUser(any()) }
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
            Result.success(AcceptInvitationResult(partnerId = "user-b", role = "dad"))

        val result = repository.redeem("  4f7k2m ")

        assertTrue(result.isSuccess)
        coVerify { pairingFunctions.acceptInvitation(code = "4F7K2M", invitationId = null) }
    }

    @Test
    fun `redeem surfaces the newly assigned slot from the callable, the same as acceptIncoming`() = runTest {
        // QR scan, manual code entry and deep link all funnel through redeem(), and reach the
        // same acceptPairingInvitation callable as an addressed-invitation accept — this path
        // must carry the role through too, or PairingViewModel has nothing to re-stamp with.
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.success(AcceptInvitationResult(partnerId = "user-b", role = "dad"))

        val result = repository.redeem("4F7K2M")

        assertEquals("dad", result.getOrNull())
    }

    @Test
    fun `redeem surfaces a null role without failing when the callable does not report one`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.success(AcceptInvitationResult(partnerId = "user-b", role = null))

        val result = repository.redeem("4F7K2M")

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }

    // ---- acceptIncoming ----------------------------------------------------

    @Test
    fun `acceptIncoming surfaces the newly assigned slot from the callable`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(invitationId = "invite-1") } returns
            Result.success(AcceptInvitationResult(partnerId = "user-b", role = "dad"))

        val result = repository.acceptIncoming("invite-1")

        assertEquals("dad", result.getOrNull())
    }

    @Test
    fun `acceptIncoming surfaces a null role without failing when the callable does not report one`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(invitationId = "invite-1") } returns
            Result.success(AcceptInvitationResult(partnerId = "user-b", role = null))

        val result = repository.acceptIncoming("invite-1")

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `acceptIncoming surfaces the backend error unchanged`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(invitationId = "invite-1") } returns
            Result.failure(PairingException(PairingError.NotFound))

        val result = repository.acceptIncoming("invite-1")

        assertEquals(
            PairingError.NotFound,
            (result.exceptionOrNull() as PairingException).error
        )
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

    /**
     * Knock-on of the profile gap, not a regression test for the fix itself.
     *
     * `writeNewInvite` takes `fromUserName` from the inviter's own profile document and
     * falls back to the raw email address. That name is what the share text says and what
     * the co-parent's incoming-invitation card shows, so while nothing wrote a profile the
     * invitation was degraded in exactly the same way the partner card was. These two cases
     * pin the dependency: the fallback is only reached when the profile carries no name.
     */
    @Test
    fun `a new invite carries the profile name once the profile has one`() = runTest {
        every { userSnapshot.getString("name") } returns "Alice Novak"
        stubOwnInvitesQuery()
        val newInviteRef = mockk<DocumentReference>(relaxed = true)
        every { invitationsCollection.document(any()) } returns newInviteRef
        every { newInviteRef.set(any()) } returns Tasks.forResult<Void>(null)

        val result = repository.createOrReuseInviteCode()

        assertEquals("Alice Novak", result.getOrNull()?.fromUserName)
    }

    @Test
    fun `a new invite falls back to the email address when the profile has no name`() = runTest {
        every { userSnapshot.getString("name") } returns null
        stubOwnInvitesQuery()
        val newInviteRef = mockk<DocumentReference>(relaxed = true)
        every { invitationsCollection.document(any()) } returns newInviteRef
        every { newInviteRef.set(any()) } returns Tasks.forResult<Void>(null)

        val result = repository.createOrReuseInviteCode()

        assertEquals("a@example.com", result.getOrNull()?.fromUserName)
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

    /**
     * Handles for driving the three snapshot listeners `observePairingState` registers.
     *
     * The repository reads pairing state from realtime listeners, so a test can only move it
     * by invoking the captured [EventListener]s directly — there is no request/response to
     * stub. [emitInvites] satisfies the two invitation listeners the `combine` also waits on;
     * without it no combined value is ever produced.
     */
    private class RealtimeListeners(
        val user: () -> EventListener<DocumentSnapshot>,
        val ownInvites: () -> EventListener<QuerySnapshot>,
        val incomingInvites: () -> EventListener<QuerySnapshot>
    ) {
        fun emitUser(snapshot: DocumentSnapshot) = user().onEvent(snapshot, null)

        fun failUser(error: FirebaseFirestoreException) = user().onEvent(null, error)

        fun emitInvites() {
            val empty = mockk<QuerySnapshot>(relaxed = true)
            every { empty.documents } returns emptyList()
            ownInvites().onEvent(empty, null)
            incomingInvites().onEvent(empty, null)
        }
    }

    private fun stubRealtimeListeners(): RealtimeListeners {
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "user-a"
        every { firebaseUser.email } returns "a@example.com"
        every { authService.getAuthStateFlow() } returns flowOf(firebaseUser)

        val userListener = slot<EventListener<DocumentSnapshot>>()
        val userDocument = mockk<DocumentReference>(relaxed = true)
        every { usersCollection.document("user-a") } returns userDocument
        every { userDocument.addSnapshotListener(capture(userListener)) } returns mockk(relaxed = true)

        // Own invites are filtered by fromUserId; incoming ones by toEmail. Both land on the
        // same relaxed `invitationsQuery`, so they are told apart by which where-clause built
        // them rather than by the object itself.
        val ownQuery = mockk<Query>(relaxed = true)
        val incomingQuery = mockk<Query>(relaxed = true)
        every { invitationsCollection.whereEqualTo("fromUserId", "user-a") } returns ownQuery
        every { invitationsCollection.whereEqualTo("toEmail", "a@example.com") } returns incomingQuery
        every { ownQuery.whereEqualTo("status", "pending") } returns ownQuery
        every { incomingQuery.whereEqualTo("status", "pending") } returns incomingQuery

        val ownListener = slot<EventListener<QuerySnapshot>>()
        val incomingListener = slot<EventListener<QuerySnapshot>>()
        every { ownQuery.addSnapshotListener(capture(ownListener)) } returns mockk(relaxed = true)
        every { incomingQuery.addSnapshotListener(capture(incomingListener)) } returns mockk(relaxed = true)

        return RealtimeListeners(
            user = { userListener.captured },
            ownInvites = { ownListener.captured },
            incomingInvites = { incomingListener.captured }
        )
    }

    private fun userDoc(partnerId: String, pairedAt: Long? = null): DocumentSnapshot {
        val snapshot = mockk<DocumentSnapshot>(relaxed = true)
        every { snapshot.getString("partnerId") } returns partnerId
        every { snapshot.getLong("pairedAt") } returns pairedAt
        return snapshot
    }

    private fun userEntity(partnerId: String?) = UserEntity(
        id = "user-a",
        email = "a@example.com",
        name = "Alice",
        role = "mom",
        colorCode = "#FF4081",
        partnerId = partnerId
    )

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
        const val PARTNER_PHOTO = "https://lh3.googleusercontent.com/a/bob"
    }
}
