package com.coparently.app.data.session

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.model.AccountSummary
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Who am I signed in as", as a flow, for the screens that need to say so out loud.
 *
 * Two parents each holding a phone cannot tell their accounts apart from the pairing
 * screen alone — both phones show an invite code and nothing else identifying — so the
 * pairing screen and the Settings account card both render this.
 *
 * It combines the two halves of the identity rather than picking one:
 *
 * - **Firebase Auth** is the freshest and, for a Google account, the only source of the
 *   display name and the avatar. It is available the instant auth restores a session,
 *   before any Firestore round-trip.
 * - **The local Room row** is what survives an email/password sign-in (where Auth reports
 *   no `displayName` and no `photoUrl` at all) and is kept current by
 *   [SessionProfileSynchronizer]. Observing it, rather than reading it once, means the
 *   block fills in by itself the moment that repair lands.
 *
 * The preference between them is [ProfileIdentity]'s, the same one the write path uses, so
 * what this shows and what the co-parent reads out of Firestore cannot drift apart.
 */
@Singleton
class SignedInAccountSource @Inject constructor(
    private val authService: FirebaseAuthService,
    private val userDao: UserDao
) {

    /**
     * The signed-in account, or null when signed out (or when the account carries no
     * identity worth rendering at all).
     *
     * Re-emits on sign-in, sign-out, account switch and any local profile change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<AccountSummary?> = authService.getAuthStateFlow()
        .flatMapLatest { user ->
            if (user == null) {
                flowOf(null)
            } else {
                userDao.observeUserById(user.uid).map { row -> summarize(user, row) }
            }
        }
        // Firebase re-emits the same user on every token refresh, and Room re-emits the
        // whole row when an unrelated column (fcmToken, partnerId) changes.
        .distinctUntilChanged()

    /**
     * Folds the auth session and the stored row into one summary, or null when neither
     * carries a name or an email — a state with nothing to show, where a "signed in as"
     * block would be worse than no block at all.
     */
    private fun summarize(user: FirebaseUser, row: UserEntity?): AccountSummary? {
        val email = user.email?.takeIf { it.isNotBlank() } ?: row?.email.orEmpty()
        val name = ProfileIdentity.resolveName(
            displayName = user.displayName,
            storedRemoteName = null,
            storedLocalName = row?.name,
            email = email
        ).orEmpty()
        if (name.isBlank() && email.isBlank()) return null

        return AccountSummary(
            id = user.uid,
            name = name,
            email = email,
            photoUrl = ProfileIdentity.resolvePhotoUrl(
                authPhotoUrl = user.photoUrl?.toString(),
                storedRemoteUrl = null,
                storedLocalUrl = row?.profilePhotoUrl
            )
        )
    }
}
