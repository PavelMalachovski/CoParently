package com.coparently.app.data.session

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the signed-in user's profile document in existence and up to date, for the whole
 * session rather than for whoever happens to open a particular screen.
 *
 * Firebase Auth creates an account, not a `users/{uid}` document. Nothing else in the app
 * writes the user's own `name` and `email` there: the FCM registration writes `fcmToken`
 * with a merge (so the document exists, carrying that one key), the pairing Cloud Function
 * adds `partnerId`/`pairedAt`, and `UserRepositoryImpl.syncWithFirestore` only reads. The
 * co-parent's pairing card reads `name`/`email` from that same document, so the gap
 * surfaced on the other phone as "Unknown"/"Email unavailable".
 *
 * The auth-state boundary is the correct trigger because it is the one event every signed-in
 * user passes through:
 *
 * - `AuthStateListener` fires immediately on registration, so a session restored from a
 *   build that never wrote a profile is repaired at the next cold start — no re-sign-in and
 *   no visit to the pairing screen required;
 * - it fires again on every sign-in and account switch, so a fresh account gets a profile
 *   before it can be paired with.
 *
 * The earlier version of this logic lived in the pairing ViewModel and therefore only ever
 * ran for a user who opened the pairing screen; the pairing rewrite dropped it, which is how
 * the underlying gap became visible.
 */
@Singleton
class SessionProfileSynchronizer @Inject constructor(
    private val authService: FirebaseAuthService,
    private val userRepository: UserRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts observing the auth state for the lifetime of the process.
     *
     * Called once from `CoPlanlyApplication.onCreate`. Safe to call again — the underlying
     * work is idempotent — but there is no reason to.
     */
    fun start() {
        scope.launch { observeSessions() }
    }

    /**
     * Collects distinct signed-in UIDs and repairs the profile for each.
     *
     * Distinct on the UID rather than on the [com.google.firebase.auth.FirebaseUser]
     * instance: Firebase re-emits the same user on token refresh, and there is no point
     * re-reading the profile for that.
     */
    @VisibleForTesting
    internal suspend fun observeSessions() {
        authService.getAuthStateFlow()
            .map { it?.uid }
            .distinctUntilChanged()
            .collect { uid ->
                if (uid == null) return@collect
                Log.d(TAG, "Ensuring the profile document for the signed-in user")
                userRepository.ensureProfile()
            }
    }

    private companion object {
        const val TAG = "SessionProfileSync"
    }
}
