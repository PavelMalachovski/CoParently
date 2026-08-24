package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.coparently.app.data.session.ProfileIdentity
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserInfo
import com.google.gson.GsonBuilder
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Implementation of UserRepository.
 * Maps between domain models (User) and data layer entities (UserEntity).
 * Integrates Firebase Authentication and Firestore for multi-user support.
 *
 * At detekt's `TooManyFunctions` threshold: it must implement every [UserRepository] member —
 * itself over that same threshold, deliberately, per its own class doc — plus the private
 * mapping helpers ([toDomain], [toEntity], [toUser]) that keep Room, Firestore and the domain
 * model in sync. Splitting those helpers out would not reduce the real complexity, only hide it
 * behind another file.
 */
@Suppress("TooManyFunctions")
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreUserDataSource: FirestoreUserDataSource,
    private val fcmService: FcmService
) : UserRepository {

    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()

    override fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getUserById(id: String): User? {
        return userDao.getUserById(id)?.toDomain()
    }

    override fun observeUserById(id: String): Flow<User?> =
        userDao.observeUserById(id).map { it?.toDomain() }

    override suspend fun getUserByEmail(email: String): User? {
        return userDao.getUserByEmail(email)?.toDomain()
    }

    override suspend fun getCurrentUser(): User? {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return null

        return try {
            getUserById(firebaseUser.uid)
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Failed to get current user data", e)
            null
        }
    }

    override suspend fun getCurrentUserId(): String? = firebaseAuthService.getCurrentUser()?.uid

    override fun observeCurrentUserId(): Flow<String?> = firebaseAuthService.getAuthStateFlow()
        .map { it?.uid }
        // Firebase re-emits the same user on every token refresh; only a real session
        // change is interesting to a subscriber.
        .distinctUntilChanged()

    /**
     * Fills in the signed-in user's identity (`name`, `email`) in `users/{uid}` and in the
     * local Room row, without disturbing anything else either side already holds.
     *
     * Three properties this has to get right:
     *
     * 1. **Merge, never overwrite.** The remote write goes through
     *    [FirestoreUserDataSource.updateUser], which is `set(..., SetOptions.merge())`, and
     *    carries only the keys that actually need to change. `partnerId`, `pairedAt`,
     *    `fcmToken` and `pendingRevocationOf` are therefore untouched. This replaces the
     *    dormant `upsertUser`, a full `.set()` that would have deleted every key it did not
     *    list — including `pendingRevocationOf`, the marker `unpairCoParent` leaves behind
     *    to remember whose shared access a partial revocation sweep still has to reach.
     * 2. **No downgrade.** Name, email and photo are resolved by [ProfileIdentity] in a
     *    strict preference order, so a session where Firebase Auth's top-level fields have
     *    no `displayName`, `email` or `photoUrl` (every email/password account, and a Google
     *    account whose record predates linking the provider — see
     *    [ProfileIdentity.ProviderIdentity]) keeps whatever real identity is already stored
     *    or, failing that, falls back to `providerData` before ever resorting to the email
     *    local part. A null photo is never written: "this session does not know one" must
     *    not be mistaken for "the user removed theirs".
     * 3. **Idempotent.** Nothing is written when the stored picture already matches, so the
     *    per-session and per-sync calls cost one cached document read.
     *
     * Best-effort by design: this runs in the background off the auth-state boundary, no
     * user action is waiting on it, and the next session (or the next `SyncWorker` pass)
     * retries. Failures are logged and swallowed.
     */
    override suspend fun ensureProfile() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val uid = firebaseUser.uid

        try {
            // Null here means either "no document" or "the read failed"; both are safe,
            // because every write below is a merge and the name resolution falls back to
            // the local row rather than to a guess.
            //
            // `providers` covers the gap the other two can't: a real, non-anonymous session
            // (Google, in practice) whose account record predates linking the provider
            // reports nothing at the top level but carries the name/email/photo here. See
            // ProfileIdentity's class doc for why this is a fallback and not a competing
            // source.
            val snapshot = ProfileSnapshot(
                remote = firestoreUserDataSource.getUserById(uid),
                local = userDao.getUserById(uid),
                providers = firebaseUser.providerData.map { it.toProviderIdentity() }
            )
            val (remote, local, providers) = snapshot

            val email = ProfileIdentity.resolveEmail(
                topLevelEmail = firebaseUser.email,
                storedRemoteEmail = remote?.string("email"),
                storedLocalEmail = local?.email,
                providers = providers
            )
            val name = ProfileIdentity.resolveName(
                displayName = firebaseUser.displayName,
                storedRemoteName = remote?.string("name"),
                storedLocalName = local?.name,
                email = email,
                providers = providers
            )
            if (name == null) {
                writeIdentityWithoutName(uid, firebaseUser, snapshot, email)
                return
            }
            val identity = ResolvedIdentity(
                name = name,
                email = email.orEmpty(),
                photoUrl = ProfileIdentity.resolvePhotoUrl(
                    authPhotoUrl = firebaseUser.photoUrl?.toString(),
                    storedRemoteUrl = remote?.string("profilePhotoUrl"),
                    storedLocalUrl = local?.profilePhotoUrl,
                    providers = providers
                )
            )

            writeRemoteProfile(uid, remote, identity)
            writeLocalProfile(uid, remote, local, identity)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            android.util.Log.e(TAG, "Failed to ensure the user profile for $uid", e)
        }
    }

    /**
     * Salvages whatever identity *is* known when no display name could be derived.
     *
     * A blank name genuinely cannot be written: the `users` **create** rule requires
     * `name` to be 1..100 characters, so a merge onto a document that does not exist yet
     * is rejected outright. The **update** rule says nothing about `name` at all, so a
     * document that already exists — the `fcmToken`-only one the FCM registration leaves
     * behind is the common case — can be merged into without one. Both halves of that are
     * pinned in `firestore-tests/rules/users-profile.test.js`.
     *
     * So the skip is narrowed from "write nothing" to "write everything except the name":
     * the email address and the avatar are real data the co-parent's pairing card renders,
     * and `id`/`firebaseUid` are what keep [syncWithFirestore] from minting a random local
     * id later. Discarding them because one *other* field is unknown helped nobody.
     *
     * A null [remote] is deliberately treated as "do not write remotely". It means either
     * "no document" — where the create rule would reject this patch — or "the read failed",
     * where nothing is known about what is already stored. Neither is worth a denied write;
     * the next session retries.
     *
     * The log line is the point of the rest of this: it reports which inputs were absent
     * and what kind of session this is, so a device that keeps landing here can be
     * diagnosed instead of guessed at. See [ProfileIdentity.describeNameSources] for why
     * it carries presence flags and provider ids and no personal data — and, now that
     * [ProfileIdentity.resolveName] itself checks `providerData`, for why reaching this
     * method at all means that check already came back empty too.
     *
     * @param snapshot The same remote/local/provider data [ensureProfile] already read and
     *   built for [ProfileIdentity.resolveName]/[ProfileIdentity.resolveEmail] — passed in
     *   rather than re-read or rebuilt, so the log's `hasProviderName` reflects exactly what
     *   resolution just tried, and grouped into one parameter to stay under detekt's
     *   parameter-count limit (the same reason [ResolvedIdentity] exists).
     * @param email The output of [ProfileIdentity.resolveEmail], already provider-aware, so
     *   this method does not re-derive it with a narrower rule than the caller used.
     */
    private suspend fun writeIdentityWithoutName(
        uid: String,
        firebaseUser: FirebaseUser,
        snapshot: ProfileSnapshot,
        email: String?
    ) {
        val (remote, local, providers) = snapshot
        val photoUrl = ProfileIdentity.resolvePhotoUrl(
            authPhotoUrl = firebaseUser.photoUrl?.toString(),
            storedRemoteUrl = remote?.string("profilePhotoUrl"),
            storedLocalUrl = local?.profilePhotoUrl,
            providers = providers
        )
        val sources = ProfileIdentity.describeNameSources(
            hasDisplayName = firebaseUser.displayName?.nonBlank() != null,
            hasRemoteName = remote?.string("name") != null,
            hasLocalName = local?.name?.nonBlank() != null,
            hasProviderName = ProfileIdentity.bestProvider(providers)?.displayName?.nonBlank() != null,
            hasEmail = email != null,
            hasRemoteProfile = remote != null,
            isAnonymous = firebaseUser.isAnonymous,
            providerIds = providers.map { it.providerId }
        )
        val outcome = if (remote == null) {
            "No readable profile document, so a name-less merge would hit the create rule; " +
                "nothing written remotely."
        } else {
            "Merging the fields that are known into the existing document."
        }
        android.util.Log.w(TAG, "No name could be derived for $uid; $sources. $outcome")

        if (remote != null) {
            val patch = buildMap<String, Any> {
                email?.takeIf { it != remote.string("email") }?.let { put("email", it) }
                if (remote.string("id") == null) put("id", uid)
                if (remote.string("firebaseUid") == null) put("firebaseUid", uid)
                photoUrl?.takeIf { it != remote.string("profilePhotoUrl") }
                    ?.let { put("profilePhotoUrl", it) }
            }
            if (patch.isNotEmpty()) {
                firestoreUserDataSource.updateUser(uid, patch).onFailure {
                    android.util.Log.e(TAG, "Failed to merge the name-less profile into Firestore for $uid", it)
                }
            }
        }

        // Only an existing local row is touched. Creating one whose `name` is blank would
        // put a nameless user into every list that reads Room, which is worse than the gap.
        if (local != null) {
            val updated = local.copy(
                email = email ?: local.email,
                profilePhotoUrl = photoUrl ?: local.profilePhotoUrl
            )
            if (updated != local) userDao.insertUser(updated)
        }
    }

    /**
     * The identity [ensureProfile] resolved, on its way to both stores.
     *
     * Grouped rather than passed as three loose parameters so the two writers below stay
     * under detekt's parameter-count limit, and so a fourth identity field can be added
     * later without touching their signatures.
     */
    private data class ResolvedIdentity(
        val name: String,
        val email: String,
        val photoUrl: String?
    )

    /**
     * The remote document, local row and linked-provider data [ensureProfile] reads once and
     * hands to [writeIdentityWithoutName], for the same reason [ResolvedIdentity] exists:
     * one parameter instead of three keeps that function under detekt's parameter-count
     * limit.
     */
    private data class ProfileSnapshot(
        val remote: Map<String, Any?>?,
        val local: UserEntity?,
        val providers: List<ProfileIdentity.ProviderIdentity>
    )

    /**
     * Merges the identity keys that are missing or stale into `users/{uid}`.
     *
     * `id` and `firebaseUid` are only added when the document does not carry them:
     * [syncWithFirestore] reads `id` back and would otherwise mint a random UUID for the
     * local row, and the `users` rules require `firebaseUid`, when present, to equal the
     * caller's UID.
     *
     * `profilePhotoUrl` is only added when this session actually resolved one, so an
     * email/password sign-in — where Firebase Auth reports no photo at all — cannot blank
     * out an avatar a Google session stored earlier.
     */
    private suspend fun writeRemoteProfile(
        uid: String,
        remote: Map<String, Any?>?,
        identity: ResolvedIdentity
    ) {
        val patch = buildMap<String, Any> {
            if (remote?.string("name") != identity.name) put("name", identity.name)
            if (remote?.string("email") != identity.email.nonBlank()) put("email", identity.email)
            if (remote?.string("id") == null) put("id", uid)
            if (remote?.string("firebaseUid") == null) put("firebaseUid", uid)
            identity.photoUrl
                ?.takeIf { it != remote?.string("profilePhotoUrl") }
                ?.let { put("profilePhotoUrl", it) }
        }
        if (patch.isEmpty()) return

        firestoreUserDataSource.updateUser(uid, patch)
            .onFailure { android.util.Log.e(TAG, "Failed to merge the profile into Firestore for $uid", it) }
    }

    /**
     * Mirrors the same identity into Room, so the local picture agrees with the remote one.
     *
     * An existing row is `copy()`-ed rather than rebuilt, so role, colour, calendar
     * settings, `partnerId`, the FCM token, and this same fresh-row branch's own
     * `dateOfBirth`/`phone`/`allergiesJson`/`medicalProfileJson` survive the REPLACE insert.
     * The photo is only overwritten when one was resolved, for the same no-downgrade reason
     * as the remote patch.
     *
     * The fresh-row branch seeds `dateOfBirth`, `phone`, `allergies` and `medicalProfile` from
     * [remote] for the same reason it already seeds `partnerId`: a reinstall calls this before
     * anything else has a chance to populate Room, and [toUser] — the mapper that *does* read
     * these fields — is only ever used for [getRemoteUserProfile]'s read-only co-parent view,
     * never to persist. Leaving them at the entity defaults here meant a reinstalled device
     * created an empty local row, and the next unrelated field edit pushed that emptiness back
     * over the real values in Firestore via `updateUser`'s `set(merge)` — permanent data loss
     * for a field nothing else ever writes on its own.
     */
    private suspend fun writeLocalProfile(
        uid: String,
        remote: Map<String, Any?>?,
        local: UserEntity?,
        identity: ResolvedIdentity
    ) {
        val updated = local?.copy(
            name = identity.name,
            email = identity.email,
            profilePhotoUrl = identity.photoUrl ?: local.profilePhotoUrl
        ) ?: UserEntity(
            id = uid,
            email = identity.email,
            name = identity.name,
            role = remote?.string("role") ?: DEFAULT_ROLE,
            colorCode = remote?.string("colorCode") ?: DEFAULT_COLOR_CODE,
            profilePhotoUrl = identity.photoUrl,
            googleCalendarSyncEnabled = remote?.get("googleCalendarSyncEnabled") as? Boolean ?: false,
            googleCalendarId = remote?.string("googleCalendarId"),
            partnerId = remote?.string("partnerId"),
            fcmToken = remote?.string("fcmToken"),
            dateOfBirth = remote?.string("dateOfBirth"),
            phone = remote?.string("phone"),
            allergiesJson = (remote?.get("allergies") as? List<*>)
                ?.mapNotNull { it as? String }
                ?.let { gson.toJson(it) }
                ?: DEFAULT_ALLERGIES_JSON,
            medicalProfileJson = (remote?.get("medicalProfile") as? Map<*, *>)
                ?.let { gson.toJson(it) }
                ?: DEFAULT_MEDICAL_PROFILE_JSON,
            onboardingCompletedAt = remote?.string("onboardingCompletedAt"),
            // `?: local?.caresForKinds`, not a bare remote read: a co-parent's build that does
            // not write the field must not clear the answer this device already holds.
            caresForKinds = remote?.string("caresFor") ?: local?.caresForKinds
        )
        if (updated != local) userDao.insertUser(updated)
    }

    override suspend fun updateUser(user: User) {
        try {
            userDao.updateUser(user.toEntity())
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Failed to update user in local database", e)
            throw e
        }

        // Also sync to Firestore
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            try {
                // `role` is deliberately absent, and this is load-bearing rather than tidy.
                //
                // The parent slot is assigned by the server — `assignSlots` in
                // `functions/index.js`, inside the `acceptPairingInvitation` transaction — and
                // no client ever chooses one. Room's copy is a *mirror* that the accept path
                // does not write (see `ParentSlotMigrator.reslotIfSlotChanged`, which documents
                // why `User.role` cannot be trusted as a change detector), so it stays at the
                // pre-pairing value until a sync happens to refresh it.
                //
                // Sending it back was therefore an overwrite of the server's answer with a
                // stale guess. `updateFcmToken` below reads Room, copies one field and calls
                // this method, so an ordinary token refresh shortly after pairing pushed the
                // accepter's *old* slot over the `dad` the transaction had just written. The
                // next sync read that value back, Room agreed with it, and the pair was left
                // with both parents in slot 1 — permanently, self-consistently, and silently.
                // `momDayIndices` means "the days slot 1 has custody", so a pair in that state
                // has a custody pattern that distinguishes nobody.
                //
                // This is a `set(..., merge)` (see `FirestoreUserDataSource.updateUser`), so
                // omitting the key leaves the stored slot untouched rather than clearing it.
                val userData = mapOf(
                    "id" to user.id,
                    "firebaseUid" to firebaseUser.uid, // Required by Firestore security rules
                    "email" to user.email,
                    "name" to user.name,
                    "colorCode" to user.colorCode,
                    "profilePhotoUrl" to (user.profilePhotoUrl ?: ""),
                    "googleCalendarSyncEnabled" to user.googleCalendarSyncEnabled,
                    "googleCalendarId" to (user.googleCalendarId ?: ""),
                    "partnerId" to (user.partnerId ?: ""),
                    "fcmToken" to (user.fcmToken ?: ""),
                    "dateOfBirth" to (user.dateOfBirth?.toString() ?: ""),
                    "phone" to (user.phone ?: ""),
                    "allergies" to user.allergies,
                    "medicalProfile" to gson.fromJson(
                        gson.toJson(user.medicalProfile), Map::class.java
                    ),
                    "onboardingCompletedAt" to (user.onboardingCompletedAt ?: ""),
                    // A string of constant names, not a list: the co-parent reads it to decide
                    // whether to show child records, and the two halves must agree on one shape.
                    "caresFor" to (FamilyKind.toStored(user.caresFor) ?: "")
                )
                firestoreUserDataSource.updateUser(firebaseUser.uid, userData).getOrThrow()
            } catch (e: Exception) {
                android.util.Log.e("UserRepository", "Failed to sync user update to Firestore", e)
                // Don't throw here - local update succeeded, Firestore sync failed
            }
        }
    }

    override suspend fun deleteUser(id: String) {
        userDao.deleteUserById(id)
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        try {
            // Fetch user data from Firestore
            val firestoreData = firestoreUserDataSource.getUserById(firebaseUser.uid)
            if (firestoreData == null) {
                android.util.Log.w("UserRepository", "No user data found in Firestore for user: ${firebaseUser.uid}")
                return
            }

            // Update local database
            val user = firestoreData.toUser()
            userDao.insertUser(user.toEntity())
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "Failed to sync user data from Firestore", e)
            throw e
        }
    }

    override suspend fun updateFcmToken(token: String) {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val currentUser = getUserById(firebaseUser.uid) ?: return

        val updatedUser = currentUser.copy(fcmToken = token)
        updateUser(updatedUser)
    }

    override suspend fun getRemoteUserProfile(uid: String): User? {
        return try {
            firestoreUserDataSource.getUserById(uid)?.toUser()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            android.util.Log.e(TAG, "Failed to read the remote profile for $uid", e)
            null
        }
    }

    /** This string unless it is blank, in which case null. */
    private fun String.nonBlank(): String? = takeIf { it.isNotBlank() }

    /** The value at [key] as a non-blank string, or null. */
    private fun Map<String, Any?>.string(key: String): String? = (this[key] as? String)?.nonBlank()

    /**
     * Maps UserEntity to User domain model.
     */
    private fun UserEntity.toDomain(): User {
        return User(
            id = id,
            email = email,
            name = name,
            role = role,
            colorCode = colorCode,
            profilePhotoUrl = profilePhotoUrl,
            googleCalendarSyncEnabled = googleCalendarSyncEnabled,
            googleCalendarId = googleCalendarId,
            partnerId = partnerId,
            fcmToken = fcmToken,
            dateOfBirth = parseProfileDate(dateOfBirth),
            phone = phone,
            allergies = gson.fromJson(allergiesJson, Array<String>::class.java)?.toList().orEmpty(),
            medicalProfile = (
                gson.fromJson(medicalProfileJson, MedicalProfile::class.java) ?: MedicalProfile()
                ).withSanitizedVaccinationNames(),
            onboardingCompletedAt = onboardingCompletedAt,
            caresFor = FamilyKind.fromStored(caresForKinds)
        )
    }

    /**
     * Maps User domain model to UserEntity.
     */
    private fun User.toEntity(): UserEntity {
        return UserEntity(
            id = id,
            email = email,
            name = name,
            role = role,
            colorCode = colorCode,
            profilePhotoUrl = profilePhotoUrl,
            googleCalendarSyncEnabled = googleCalendarSyncEnabled,
            googleCalendarId = googleCalendarId,
            partnerId = partnerId,
            fcmToken = fcmToken,
            dateOfBirth = dateOfBirth?.toString(),
            phone = phone,
            allergiesJson = gson.toJson(allergies),
            medicalProfileJson = gson.toJson(medicalProfile),
            onboardingCompletedAt = onboardingCompletedAt,
            caresForKinds = FamilyKind.toStored(caresFor)
        )
    }

    /**
     * Maps Firestore user data to User domain model.
     */
    private fun Map<String, Any?>.toUser(): User {
        return User(
            id = this["id"] as? String ?: UUID.randomUUID().toString(),
            email = this["email"] as? String ?: "",
            name = this["name"] as? String ?: "",
            role = this["role"] as? String ?: "mom",
            colorCode = this["colorCode"] as? String ?: "#FF4081",
            profilePhotoUrl = this["profilePhotoUrl"] as? String,
            googleCalendarSyncEnabled = this["googleCalendarSyncEnabled"] as? Boolean ?: false,
            googleCalendarId = this["googleCalendarId"] as? String,
            partnerId = this["partnerId"] as? String,
            fcmToken = this["fcmToken"] as? String,
            dateOfBirth = parseProfileDate(this["dateOfBirth"] as? String),
            phone = (this["phone"] as? String)?.takeIf { it.isNotBlank() },
            allergies = (this["allergies"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            medicalProfile = (
                (this["medicalProfile"] as? Map<*, *>)?.let {
                    gson.fromJson(gson.toJson(it), MedicalProfile::class.java)
                } ?: MedicalProfile()
                ).withSanitizedVaccinationNames(),
            onboardingCompletedAt = (this["onboardingCompletedAt"] as? String)?.takeIf { it.isNotBlank() },
            caresFor = FamilyKind.fromStored(this["caresFor"] as? String)
        )
    }

    private companion object {
        const val TAG = "UserRepository"

        /** Same defaults [toUser] applies to a Firestore document that omits them. */
        const val DEFAULT_ROLE = "mom"
        const val DEFAULT_COLOR_CODE = "#FF4081"

        /** Same defaults [UserEntity]'s own declaration applies; named here for [writeLocalProfile]. */
        const val DEFAULT_ALLERGIES_JSON = "[]"
        const val DEFAULT_MEDICAL_PROFILE_JSON = "{}"
    }
}

/**
 * Projects one `FirebaseUser.providerData` entry into the plain-string shape
 * [ProfileIdentity] works with, so that pure object never has to depend on the Firebase
 * `UserInfo`/`android.net.Uri` types.
 *
 * Kept as a top-level function rather than a member of [UserRepositoryImpl]: it needs no
 * access to that class's state, and [UserRepositoryImpl] is already at detekt's
 * function-count limit.
 */
private fun UserInfo.toProviderIdentity() = ProfileIdentity.ProviderIdentity(
    providerId = providerId,
    displayName = displayName,
    email = email,
    photoUrl = photoUrl?.toString()
)

