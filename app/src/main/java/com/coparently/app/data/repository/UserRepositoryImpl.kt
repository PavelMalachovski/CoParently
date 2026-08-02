package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of UserRepository.
 * Maps between domain models (User) and data layer entities (UserEntity).
 * Integrates Firebase Authentication and Firestore for multi-user support.
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreUserDataSource: FirestoreUserDataSource,
    private val fcmService: FcmService
) : UserRepository {

    override fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getUserById(id: String): User? {
        return userDao.getUserById(id)?.toDomain()
    }

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
     * 2. **No downgrade.** The name is resolved by [resolveName] in a strict preference
     *    order, so a session where Firebase Auth has no `displayName` (every
     *    email/password account) keeps whatever real name is already stored instead of
     *    falling back to the email local part.
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
            val remote = firestoreUserDataSource.getUserById(uid)
            val local = userDao.getUserById(uid)

            val name = resolveName(
                displayName = firebaseUser.displayName,
                storedRemoteName = remote?.string("name"),
                storedLocalName = local?.name,
                email = firebaseUser.email ?: local?.email
            )
            if (name == null) {
                // A profile with a blank name is rejected by the `users` create rule and
                // would render as "Unknown" anyway — better to retry next session.
                android.util.Log.w(TAG, "No name could be derived for $uid; skipping profile write")
                return
            }
            val email = firebaseUser.email?.nonBlank() ?: remote?.string("email") ?: local?.email.orEmpty()

            writeRemoteProfile(uid, remote, name, email)
            writeLocalProfile(uid, remote, local, name, email)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            android.util.Log.e(TAG, "Failed to ensure the user profile for $uid", e)
        }
    }

    /**
     * Merges the identity keys that are missing or stale into `users/{uid}`.
     *
     * `id` and `firebaseUid` are only added when the document does not carry them:
     * [syncWithFirestore] reads `id` back and would otherwise mint a random UUID for the
     * local row, and the `users` rules require `firebaseUid`, when present, to equal the
     * caller's UID.
     */
    private suspend fun writeRemoteProfile(
        uid: String,
        remote: Map<String, Any?>?,
        name: String,
        email: String
    ) {
        val patch = buildMap<String, Any> {
            if (remote?.string("name") != name) put("name", name)
            if (remote?.string("email") != email.nonBlank()) put("email", email)
            if (remote?.string("id") == null) put("id", uid)
            if (remote?.string("firebaseUid") == null) put("firebaseUid", uid)
        }
        if (patch.isEmpty()) return

        firestoreUserDataSource.updateUser(uid, patch)
            .onFailure { android.util.Log.e(TAG, "Failed to merge the profile into Firestore for $uid", it) }
    }

    /**
     * Mirrors the same identity into Room, so the local picture agrees with the remote one.
     *
     * An existing row is `copy()`-ed rather than rebuilt, so role, colour, calendar
     * settings, `partnerId` and the FCM token survive the REPLACE insert.
     */
    private suspend fun writeLocalProfile(
        uid: String,
        remote: Map<String, Any?>?,
        local: UserEntity?,
        name: String,
        email: String
    ) {
        val updated = local?.copy(name = name, email = email) ?: UserEntity(
            id = uid,
            email = email,
            name = name,
            role = remote?.string("role") ?: DEFAULT_ROLE,
            colorCode = remote?.string("colorCode") ?: DEFAULT_COLOR_CODE,
            profilePhotoUrl = remote?.string("profilePhotoUrl"),
            googleCalendarSyncEnabled = remote?.get("googleCalendarSyncEnabled") as? Boolean ?: false,
            googleCalendarId = remote?.string("googleCalendarId"),
            partnerId = remote?.string("partnerId"),
            fcmToken = remote?.string("fcmToken")
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
                val userData = mapOf(
                    "id" to user.id,
                    "firebaseUid" to firebaseUser.uid, // Required by Firestore security rules
                    "email" to user.email,
                    "name" to user.name,
                    "role" to user.role,
                    "colorCode" to user.colorCode,
                    "profilePhotoUrl" to (user.profilePhotoUrl ?: ""),
                    "googleCalendarSyncEnabled" to user.googleCalendarSyncEnabled,
                    "googleCalendarId" to (user.googleCalendarId ?: ""),
                    "partnerId" to (user.partnerId ?: ""),
                    "fcmToken" to (user.fcmToken ?: "")
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

    /**
     * Picks the best available display name, or null when there is nothing usable.
     *
     * Order, strongest first:
     *
     * 1. the Firebase Auth `displayName` — the authoritative identity, and the only source
     *    that self-heals once a Google account starts providing one;
     * 2. the name already stored remotely, then locally — this is the rung that matters for
     *    email/password accounts, where `displayName` is always null and step 1 must not be
     *    allowed to demote a real name to the email local part;
     * 3. the local part of the email address, the same last resort the pre-rewrite pairing
     *    screen used.
     */
    private fun resolveName(
        displayName: String?,
        storedRemoteName: String?,
        storedLocalName: String?,
        email: String?
    ): String? = displayName?.nonBlank()
        ?: storedRemoteName?.nonBlank()
        ?: storedLocalName?.nonBlank()
        ?: email?.substringBefore("@")?.nonBlank()

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
            fcmToken = fcmToken
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
            fcmToken = fcmToken
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
            fcmToken = this["fcmToken"] as? String
        )
    }

    private companion object {
        const val TAG = "UserRepository"

        /** Same defaults [toUser] applies to a Firestore document that omits them. */
        const val DEFAULT_ROLE = "mom"
        const val DEFAULT_COLOR_CODE = "#FF4081"
    }
}

