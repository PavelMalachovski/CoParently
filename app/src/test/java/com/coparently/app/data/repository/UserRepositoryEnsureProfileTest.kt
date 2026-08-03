package com.coparently.app.data.repository

import android.net.Uri
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserInfo
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
    fun `recovers name, email and photo from providerData when the top-level fields are empty`() = runTest {
        // The owner's Samsung, reproduced: a genuine Google session whose account record
        // predates linking the provider, so displayName and email are empty at the top
        // level while the google.com entry in providerData carries all three.
        signedIn(
            displayName = null,
            email = null,
            photoUrl = null,
            providers = listOf(
                googleProviderInfo(
                    displayName = "Alice Novak",
                    email = "alice@example.com",
                    photoUrl = PHOTO
                )
            )
        )
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")

        repository.ensureProfile()

        val patch = capturedRemotePatch()
        assertEquals("Alice Novak", patch["name"])
        assertEquals("alice@example.com", patch["email"])
        assertEquals(PHOTO, patch["profilePhotoUrl"])
    }

    @Test
    fun `does not downgrade a stored name with a provider value`() = runTest {
        signedIn(
            displayName = null,
            email = null,
            providers = listOf(googleProviderInfo(displayName = "Alice N. (Google)", email = "alice@example.com"))
        )
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "name" to "Alice Novak",
            "email" to "alice@example.com"
        )

        repository.ensureProfile()

        // The document is missing `id`/`firebaseUid`, so a write still happens, but it must
        // not carry a name — the provider's value must not demote the one already stored.
        assertFalse(capturedRemotePatch().containsKey("name"))
    }

    @Test
    fun `ignores the synthetic firebase entry as a name source`() = runTest {
        signedIn(displayName = null, email = null, providers = listOf(syntheticProviderInfo()))
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")

        repository.ensureProfile()

        // Falls through to the name-less path exactly as if providerData were empty.
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
    fun `carries the google account photo into the profile`() = runTest {
        // The avatar the pairing screen and the co-parent's card render comes from here:
        // Firebase Auth is the only source of it, and before this it was simply dropped.
        signedIn(displayName = "Alice Novak", email = "alice@example.com", photoUrl = PHOTO)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")

        repository.ensureProfile()

        assertEquals(PHOTO, capturedRemotePatch()["profilePhotoUrl"])
        val row = slot<UserEntity>()
        coVerify { userDao.insertUser(capture(row)) }
        assertEquals(PHOTO, row.captured.profilePhotoUrl)
    }

    @Test
    fun `does not downgrade a stored photo when auth has none`() = runTest {
        // Every email/password session looks like this, and so does a Google session whose
        // photo failed to load into the auth object. Neither is the user deleting a photo.
        signedIn(displayName = null, email = "alice@example.com", photoUrl = null)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "name" to "Alice Novak",
            "email" to "alice@example.com",
            "profilePhotoUrl" to PHOTO
        )

        repository.ensureProfile()

        assertFalse(capturedRemotePatch().containsKey("profilePhotoUrl"))
    }

    @Test
    fun `never clears a stored photo on the local row either`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com", photoUrl = null)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns emptyMap()
        coEvery { userDao.getUserById(UID) } returns localRow(name = "").copy(profilePhotoUrl = PHOTO)

        repository.ensureProfile()

        val row = slot<UserEntity>()
        coVerify { userDao.insertUser(capture(row)) }
        assertEquals(PHOTO, row.captured.profilePhotoUrl)
    }

    @Test
    fun `a newer photo from auth replaces the stored one`() = runTest {
        // Firebase Auth is the authoritative source, so a changed Google avatar wins.
        signedIn(displayName = "Alice Novak", email = "alice@example.com", photoUrl = NEW_PHOTO)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "id" to UID,
            "firebaseUid" to UID,
            "name" to "Alice Novak",
            "email" to "alice@example.com",
            "profilePhotoUrl" to PHOTO
        )

        repository.ensureProfile()

        assertEquals(NEW_PHOTO, capturedRemotePatch()["profilePhotoUrl"])
    }

    @Test
    fun `an unchanged photo is not rewritten`() = runTest {
        signedIn(displayName = "Alice Novak", email = "alice@example.com", photoUrl = PHOTO)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf(
            "id" to UID,
            "firebaseUid" to UID,
            "name" to "Alice Novak",
            "email" to "alice@example.com",
            "profilePhotoUrl" to PHOTO
        )
        coEvery { userDao.getUserById(UID) } returns
            localRow(name = "Alice Novak").copy(profilePhotoUrl = PHOTO)

        repository.ensureProfile()

        coVerify(exactly = 0) { firestoreUserDataSource.updateUser(any(), any()) }
        coVerify(exactly = 0) { userDao.insertUser(any()) }
    }

    @Test
    fun `writes nothing when nobody is signed in`() = runTest {
        every { authService.getCurrentUser() } returns null

        repository.ensureProfile()

        coVerify(exactly = 0) { firestoreUserDataSource.updateUser(any(), any()) }
        coVerify(exactly = 0) { userDao.insertUser(any()) }
    }

    @Test
    fun `writes nothing remotely when there is no document to merge a name-less profile into`() = runTest {
        // A merge onto a missing document is a *create*, and the `users` create rule
        // requires a name of 1..100 characters — so this patch could only be denied.
        // A null read is also indistinguishable from a failed one, which is the second
        // reason not to guess. Retrying next session beats a rejected write.
        signedIn(displayName = null, email = null)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns null

        repository.ensureProfile()

        coVerify(exactly = 0) { firestoreUserDataSource.updateUser(any(), any()) }
        coVerify(exactly = 0) { userDao.insertUser(any()) }
    }

    @Test
    fun `writes the fields it does know when no name can be derived but the document exists`() = runTest {
        // The Samsung's shape: Firebase Auth reports neither a display name nor an email,
        // and the only document is the one the FCM registration created. The `users`
        // *update* rule says nothing about `name`, so everything else is still writable —
        // abandoning the photo and the ids because one other field is unknown helped nobody.
        signedIn(displayName = null, email = null, photoUrl = PHOTO)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")

        repository.ensureProfile()

        val patch = capturedRemotePatch()
        assertFalse("a blank name must never be written", patch.containsKey("name"))
        assertEquals(UID, patch["id"])
        assertEquals(UID, patch["firebaseUid"])
        assertEquals(PHOTO, patch["profilePhotoUrl"])
    }

    @Test
    fun `a name-less session still refreshes the local row without inventing a name`() = runTest {
        signedIn(displayName = null, email = null, photoUrl = NEW_PHOTO)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")
        // Email blank as well: an address on the local row would itself yield a name.
        coEvery { userDao.getUserById(UID) } returns
            localRow(name = "").copy(email = "", profilePhotoUrl = PHOTO)

        repository.ensureProfile()

        val row = slot<UserEntity>()
        coVerify { userDao.insertUser(capture(row)) }
        assertEquals(NEW_PHOTO, row.captured.profilePhotoUrl)
        assertEquals("", row.captured.name)
    }

    @Test
    fun `a name-less session does not create a local row out of nothing`() = runTest {
        // A row whose name is blank would put a nameless user into every list that reads
        // Room; the gap is the lesser evil until a name turns up.
        signedIn(displayName = null, email = null)
        coEvery { firestoreUserDataSource.getUserById(UID) } returns mapOf("fcmToken" to "token-1")
        coEvery { userDao.getUserById(UID) } returns null

        repository.ensureProfile()

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

    /**
     * @param photoUrl What `FirebaseUser.photoUrl` reports. Google sign-in populates it;
     *   an email/password account never does, which is why the default here is null.
     * @param providers What `FirebaseUser.providerData` reports. Empty by default — an
     *   explicit empty list, not a relaxed-mock stub, so the name-less diagnostic's
     *   provider ids never carry a mock identifier by accident.
     */
    private fun signedIn(
        displayName: String?,
        email: String?,
        photoUrl: String? = null,
        providers: List<UserInfo> = emptyList()
    ) {
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns UID
        every { firebaseUser.displayName } returns displayName
        every { firebaseUser.email } returns email
        // Explicit even when null: a relaxed mock would hand back a stub Uri whose
        // toString() is a mock identifier, and that would be written as a photo URL.
        every { firebaseUser.photoUrl } returns photoUrl?.let { url ->
            mockk<Uri>(relaxed = true).also { uri -> every { uri.toString() } returns url }
        }
        every { firebaseUser.providerData } returns providers.toMutableList()
        every { firebaseUser.isAnonymous } returns false
        every { authService.getCurrentUser() } returns firebaseUser
    }

    /**
     * A `UserInfo` entry as `google.com` reports it: name, email and photo together, the
     * one Firebase provider this app treats as authoritative when the top-level session
     * fields are empty. See [ProfileIdentity.bestProvider] for why `google.com` wins over
     * any other real provider that might also be linked.
     */
    private fun googleProviderInfo(
        displayName: String? = null,
        email: String? = null,
        photoUrl: String? = null
    ): UserInfo {
        val info = mockk<UserInfo>(relaxed = true)
        every { info.providerId } returns "google.com"
        every { info.displayName } returns displayName
        every { info.email } returns email
        every { info.photoUrl } returns photoUrl?.let { url ->
            mockk<Uri>(relaxed = true).also { uri -> every { uri.toString() } returns url }
        }
        return info
    }

    /** The synthetic `firebase` entry every account carries — never a source of identity. */
    private fun syntheticProviderInfo(): UserInfo {
        val info = mockk<UserInfo>(relaxed = true)
        every { info.providerId } returns "firebase"
        every { info.displayName } returns "should never surface"
        every { info.email } returns "synthetic@example.com"
        every { info.photoUrl } returns mockk<Uri>(relaxed = true).also { uri ->
            every { uri.toString() } returns "https://example.com/synthetic.png"
        }
        return info
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
        const val PHOTO = "https://lh3.googleusercontent.com/a/alice"
        const val NEW_PHOTO = "https://lh3.googleusercontent.com/a/alice-v2"
    }
}
