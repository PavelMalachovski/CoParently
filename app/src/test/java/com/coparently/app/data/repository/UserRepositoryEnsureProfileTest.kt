package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [UserRepositoryImpl.ensureProfile].
 *
 * Before this method existed, nothing in the app ever wrote `name` or `email` into
 * `users/{uid}`: Firebase Auth creates the account only, the FCM registration merges a
 * lone `fcmToken` key (which is how the document came to exist at all), the pairing Cloud
 * Function adds `partnerId`/`pairedAt`, and `syncWithFirestore` only reads. The co-parent's
 * pairing card reads `name`/`email` from that document, so it showed
 * "Unknown"/"Email unavailable" for a perfectly valid pairing.
 *
 * The three properties pinned here are the ones that make the fix safe rather than merely
 * present: it writes on the right trigger, it merges instead of overwriting the keys other
 * writers own, and it never replaces a stored name with a weaker guess.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryEnsureProfileTest {

    private lateinit var userDao: UserDao
    private lateinit var authService: FirebaseAuthService
    private lateinit var firestoreUserDataSource: FirestoreUserDataSource
    private lateinit var fcmService: FcmService
    private lateinit var repository: UserRepositoryImpl

    @Before
    fun setUp() {
        userDao = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        firestoreUserDataSource = mockk(relaxed = true)
        fcmService = mockk(relaxed = true)

        coEvery { firestoreUserDataSource.updateUser(any(), any()) } returns Result.success(Unit)
        coEvery { userDao.getUserById(any()) } returns null

        repository = UserRepositoryImpl(userDao, authService, firestoreUserDataSource, fcmService)
    }

    @Test
    fun `writes name and email into a document that only carries an fcm token`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        // Exactly what the device hit: FcmService created users/{uid} with one key.
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")

        repository.ensureProfile()

        assertEquals("Alice Novak", capturedRemotePatch()["name"])
        assertEquals("alice@example.com", capturedRemotePatch()["email"])
    }

    @Test
    fun `merges rather than overwriting the keys other writers own`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "fcmToken" to "token-1",
            "partnerId" to PARTNER,
            "pairedAt" to 1_754_000_000_000L,
            "pendingRevocationOf" to listOf(PARTNER)
        )

        repository.ensureProfile()

        // The patch is applied through the merging write, and mentions none of the keys
        // the pairing function and the unpair sweep own — so none of them can be deleted.
        val patch = capturedRemotePatch()
        assertEquals(setOf("name", "email", "id", "firebaseUid"), patch.keys)
    }

    @Test
    fun `falls back to the email local part when auth has no display name`() = runTest {
        signedIn(displayName = null, email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns emptyMap()

        repository.ensureProfile()

        assertEquals("alice", capturedRemotePatch()["name"])
    }

    @Test
    fun `does not downgrade a stored name when auth has no display name`() = runTest {
        // Every email/password session looks like this: displayName is null, and the only
        // real name in the system is the one already stored.
        signedIn(displayName = null, email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "name" to "Alice Novak",
            "email" to "alice@example.com"
        )

        repository.ensureProfile()

        // The write still happens (this document is missing `id`/`firebaseUid`), but it
        // must not carry a name — "alice", the email local part, would be a downgrade.
        assertFalse(capturedRemotePatch().containsKey("name"))
    }

    @Test
    fun `writes nothing when the stored identity is already current`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "id" to UID,
            "firebaseUid" to UID,
            "name" to "Alice Novak",
            "email" to "alice@example.com"
        )
        coEvery { userDao.getUserById(UID) } returns localRow(name = "Alice Novak")

        repository.ensureProfile()

        // Runs on every session start and every background sync, so a no-op has to cost
        // one document read and nothing else.
        coVerify(exactly = 0) { firestoreUserDataSource.updateUser(any(), any()) }
        coVerify(exactly = 0) { userDao.insertUser(any()) }
    }

    @Test
    fun `does not downgrade a stored name when the profile read fails`() = runTest {
        // A failed read is indistinguishable from a missing document at the data source,
        // so the local row is the fallback that keeps a real name from being demoted.
        signedIn(displayName = null, email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns null
        coEvery { userDao.getUserById(UID) } returns localRow(name = "Alice Novak")

        repository.ensureProfile()

        assertEquals("Alice Novak", capturedRemotePatch()["name"])
    }

    @Test
    fun `creates the local row so the local and remote pictures agree`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "fcmToken" to "token-1",
            "partnerId" to PARTNER
        )

        repository.ensureProfile()

        val row = slot<UserEntity>()
        coVerify { userDao.insertUser(capture(row)) }
        assertEquals(UID, row.captured.id)
        assertEquals("Alice Novak", row.captured.name)
        assertEquals("alice@example.com", row.captured.email)
        // Seeded from the remote document, so `SyncService` and the expense/budget filters
        // do not have to wait for the next download to learn about the pairing.
        assertEquals(PARTNER, row.captured.partnerId)
    }

    @Test
    fun `updating an existing local row preserves everything but the identity`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns emptyMap()
        coEvery { userDao.getUserById(UID) } returns localRow(name = "").copy(
            role = "dad",
            partnerId = PARTNER,
            fcmToken = "token-1"
        )

        repository.ensureProfile()

        val row = slot<UserEntity>()
        coVerify { userDao.insertUser(capture(row)) }
        assertEquals("Alice Novak", row.captured.name)
        assertEquals("dad", row.captured.role)
        assertEquals(PARTNER, row.captured.partnerId)
        assertEquals("token-1", row.captured.fcmToken)
    }

    @Test
    fun `writes nothing when nobody is signed in`() = runTest {
        every { authService.getCurrentUser() } returns null

        repository.ensureProfile()

        coVerify(exactly = 0) { firestoreUserDataSource.updateUser(any(), any()) }
        coVerify(exactly = 0) { userDao.insertUser(any()) }
    }

    @Test
    fun `writes nothing when no name can be derived`() = runTest {
        // The `users` create rule requires a name of at least one character, and a blank
        // one would render as "Unknown" anyway — retrying next session beats writing junk.
        signedIn(displayName = null, email = null)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns emptyMap()

        repository.ensureProfile()

        coVerify(exactly = 0) { firestoreUserDataSource.updateUser(any(), any()) }
        coVerify(exactly = 0) { userDao.insertUser(any()) }
    }

    @Test
    fun `a failing remote write still leaves the local row correct`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns emptyMap()
        coEvery { firestoreUserDataSource.updateUser(any(), any()) } returns
            Result.failure(IllegalStateException("offline"))

        repository.ensureProfile()

        val row = slot<UserEntity>()
        coVerify { userDao.insertUser(capture(row)) }
        assertEquals("Alice Novak", row.captured.name)
    }

    @Test
    fun `seeds the id so a later firestore sync does not mint a random one`() = runTest {
        // `syncWithFirestore` maps the document back through `id`, defaulting to a random
        // UUID — a document without one would produce a local row nothing can find again.
        signedIn(displayName = "Alice Novak", email = "alice@example.com")
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "t")

        repository.ensureProfile()

        assertEquals(UID, capturedRemotePatch()["id"])
        assertEquals(UID, capturedRemotePatch()["firebaseUid"])
        assertNull(capturedRemotePatch()["partnerId"])
        assertFalse(capturedRemotePatch().containsKey("fcmToken"))
    }

    private fun signedIn(displayName: String?, email: String?) {
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns UID
        every { firebaseUser.displayName } returns displayName
        every { firebaseUser.email } returns email
        every { authService.getCurrentUser() } returns firebaseUser
    }

    private fun localRow(name: String) = UserEntity(
        id = UID,
        email = "alice@example.com",
        name = name,
        role = "mom",
        colorCode = "#FF4081"
    )

    /** The map handed to the merging Firestore write. */
    private suspend fun capturedRemotePatch(): Map<String, Any?> {
        val patch = slot<Map<String, Any?>>()
        coVerify { firestoreUserDataSource.updateUser(UID, capture(patch)) }
        return patch.captured
    }

    private companion object {
        const val UID = "user-a"
        const val PARTNER = "user-b"
    }
}
