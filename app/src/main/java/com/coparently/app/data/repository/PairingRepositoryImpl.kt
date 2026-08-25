package com.coparently.app.data.repository

import android.content.Context
import android.util.Log
import com.coparently.app.R
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.repository.PairingRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-backed [PairingRepository].
 *
 * Reads are realtime snapshot listeners; the two writes that touch the other
 * parent's document (accept, unpair) go through Cloud Functions.
 */
@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authService: FirebaseAuthService,
    private val pairingFunctions: PairingFunctions,
    private val postPairingConversationSetup: PostPairingConversationSetup,
    private val userDao: UserDao,
    // Application context only, for the localized conversation-title fallback. This is a
    // repository, not a ViewModel, so it is allowed to resolve resources directly.
    @ApplicationContext private val context: Context
) : PairingRepository {

    /**
     * The `(uid, partnerId)` pair last mirrored into Room by [onPairingStateObserved], so
     * repeated emissions of an unchanged link do no work. Null means "nothing applied yet".
     */
    private val appliedPairing = AtomicReference<Pair<String, String?>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePairingState(): Flow<PairingState> =
        authService.getAuthStateFlow()
            .flatMapLatest { user ->
                if (user == null) {
                    // Signed out (or auth has not restored its session yet). There is no
                    // dedicated "unauthenticated" case in PairingState, and Loading is
                    // accurate either way: as soon as auth resolves to a user, flatMapLatest
                    // restarts this branch with the real Firestore-backed flow below.
                    flowOf(PairingState.Loading)
                } else {
                    combine(
                        observeUserDocument(user.uid),
                        observeOwnInvites(user.uid),
                        observeIncomingInvites(user.email.orEmpty())
                    ) { userSnapshot, own, incoming ->
                        val partnerId = userSnapshot?.getString("partnerId").orEmpty()
                        if (partnerId.isEmpty()) {
                            PairingState.NotPaired(activeInvite = own.firstOrNull(), incoming = incoming)
                        } else {
                            val pairedAt = userSnapshot?.getLong("pairedAt")
                            PairingState.Paired(
                                partner = runCatching { loadPartner(partnerId, pairedAt) }
                                    .getOrElse {
                                        // The UI renders a blank name identically whether the
                                        // profile could not be read or genuinely has none; the
                                        // log is what tells the two apart after the fact.
                                        Log.w(TAG, "Could not read the partner profile $partnerId", it)
                                        PartnerSummary(
                                            id = partnerId,
                                            name = "",
                                            email = "",
                                            pairedSinceMillis = pairedAt
                                        )
                                    }
                            )
                        }
                    }
                }
            }
            // The very first value a new subscriber sees, before auth resolves or the
            // first Firestore snapshot arrives.
            .onStart { emit(PairingState.Loading) }
            .distinctUntilChanged()
            // Mirror every observed transition into Room. This hook, rather than the
            // redeem/acceptIncoming call sites, is what makes both phones learn about the
            // link: the inviter never calls anything, it only sees its own snapshot.
            .onEach { onPairingStateObserved(it) }
            // A transient failure (offline with no cache, a rules mismatch before task 11
            // deploys) must not permanently end this flow — that would freeze the pairing
            // screen on stale content forever. Recover to Loading instead; the underlying
            // snapshot listeners keep retrying on their own.
            .catch { emit(PairingState.Loading) }

    override suspend fun createOrReuseInviteCode(): Result<PairingInvite> = runPairing {
        val user = requireUser()
        val existing = firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", user.uid)
            .whereEqualTo("status", STATUS_PENDING)
            .get()
            .await()
            .documents
            .toActiveCodeInvites()
            .firstOrNull()

        existing ?: writeNewInvite(toEmail = "")
    }

    /**
     * Withdraws only this user's active code/QR/link invite (the one
     * [createOrReuseInviteCode] returns) — pending email invitations sent via
     * [sendEmailInvitation] are a separate, longer-lived offer and are left alone.
     */
    override suspend fun revokeActiveInvite(): Result<Unit> = runPairing {
        val user = requireUser()
        firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", user.uid)
            .whereEqualTo("status", STATUS_PENDING)
            .get()
            .await()
            .documents
            .filter { it.getString("toEmail").orEmpty().isEmpty() }
            .filter { it.getString("kind") != KIND_GUEST }
            .forEach { it.reference.update("status", STATUS_CANCELLED).await() }
    }

    override suspend fun sendEmailInvitation(email: String): Result<Unit> {
        val normalized = email.trim().lowercase()
        if (!EMAIL_REGEX.matches(normalized)) {
            return Result.failure(PairingException(PairingError.Unknown("Invalid email address")))
        }
        return runPairing { writeNewInvite(toEmail = normalized) }
    }

    override suspend fun redeem(code: String): Result<String?> {
        val normalized = code.trim().uppercase()
        if (!InviteCodeGenerator.isValid(normalized)) {
            return Result.failure(PairingException(PairingError.NotFound))
        }
        // Conversation creation deliberately does NOT hang off this call: it is driven by the
        // observed Paired transition in [onPairingStateObserved] instead, so the inviter's
        // phone — which never calls anything — ends up with a thread too.
        return pairingFunctions.acceptInvitation(code = normalized).map { it.role }
    }

    override suspend fun acceptIncoming(invitationId: String): Result<String?> =
        pairingFunctions.acceptInvitation(invitationId = invitationId).map { it.role }

    override suspend fun rejectIncoming(invitationId: String): Result<Unit> = runPairing {
        firestore.collection(INVITATIONS).document(invitationId)
            .update("status", STATUS_REJECTED)
            .await()
    }

    override suspend fun unpair(): Result<Unit> = pairingFunctions.unpair().map { }

    // ---- Local mirroring ------------------------------------------------

    /**
     * Mirrors an observed pairing transition into the local Room `users` row and, on a
     * transition into [PairingState.Paired], makes sure the 1:1 conversation exists.
     *
     * Everything outside the pairing screen reads pairing from Room, not from Firestore:
     * `ChatViewModel` decides between "open chat" and "go pair" from it, `ExpenseRepositoryImpl`
     * and `BudgetRepositoryImpl` derive the `familyId` they query on from it, `SyncService`
     * sizes the event audience with it, and `HomeViewModel` renders its CTA from it. Before
     * this hook the only writer was `UserRepositoryImpl.pullOnce()` behind a 15-minute
     * `SyncWorker`, so both phones showed "Paired with X" while chat, expenses, budgets and
     * events stayed unpaired for up to a quarter of an hour — and after an unpair, the
     * ex-partner's records stayed in view just as long.
     *
     * It hangs off the *observed* state rather than off [redeem]/[acceptIncoming] so it fires
     * on both devices: the inviter's phone never calls anything, it learns about the pairing
     * from its own snapshot listener. That is also why conversation creation moved here from
     * those two call sites — previously only the accepting device created a thread.
     *
     * Best-effort by design: the pairing itself is already durable in Firestore, so a local
     * write failure must never turn into a failed pairing. It is logged and swallowed, and
     * the next emission (or the `SyncWorker`) retries.
     */
    private suspend fun onPairingStateObserved(state: PairingState) {
        val uid = authService.getCurrentUser()?.uid ?: return
        val partnerId = when (state) {
            // Loading carries no information about the link — mirroring it would clear a
            // perfectly good partnerId every time the flow recovers from a transient error.
            PairingState.Loading -> return
            is PairingState.NotPaired -> null
            is PairingState.Paired -> state.partner.id.takeIf { it.isNotBlank() }
        }
        // NotPaired also re-emits whenever an invite list changes, so skip the work unless
        // the link itself moved. Keyed by uid as well, so a different account signing in is
        // never mistaken for "already applied".
        if (appliedPairing.getAndSet(uid to partnerId) == uid to partnerId) return

        try {
            val local = userDao.getUserById(uid)
            if (local != null && local.partnerId != partnerId) {
                userDao.updateUser(local.copy(partnerId = partnerId))
            }
            if (partnerId != null) ensureConversationWith(partnerId)
        } catch (e: CancellationException) {
            // Reset before rethrowing, for the same reason the generic branch below does. This
            // flow is collected under `WhileSubscribed`, so leaving the app mid-`await()`
            // cancels it — and a marker left set here means the conversation document is never
            // created for the rest of the process, which the `messages` create rule turns into
            // "every send from this device is denied".
            appliedPairing.set(null)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // Reset so the next emission retries rather than believing this one landed.
            appliedPairing.set(null)
            Log.w(TAG, "Failed to mirror the pairing transition into Room", e)
        }
    }

    // ---- Firestore plumbing -------------------------------------------

    /**
     * The user document, or nothing at all when the read fails.
     *
     * A failed read is deliberately *not* forwarded as a null snapshot. Downstream, a null
     * snapshot reads as `partnerId == ""`, i.e. "definitely not paired" — so a transient
     * error would have flipped a paired account to [PairingState.NotPaired] and offered it
     * an invite to create, and a partially-read snapshot would have produced a
     * [PairingState.Paired] carrying a blank partner as if it were real. Emitting nothing
     * leaves the combined state where it was (or at [PairingState.Loading] if this is the
     * first snapshot), and Firestore keeps retrying the listener on its own.
     */
    private fun observeUserDocument(uid: String): Flow<DocumentSnapshot?> = callbackFlow {
        val registration = firestore.collection(USERS).document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User document listener failed for uid=$uid", error)
                    return@addSnapshotListener
                }
                trySend(snapshot)
            }
        awaitClose { registration.remove() }
    }

    private fun observeOwnInvites(uid: String): Flow<List<PairingInvite>> = callbackFlow {
        val registration = firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", uid)
            .whereEqualTo("status", STATUS_PENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Own-invitations listener failed for uid=$uid", error)
                }
                trySend(snapshot?.documents.orEmpty().toActiveCodeInvites())
            }
        awaitClose { registration.remove() }
    }

    private fun observeIncomingInvites(email: String): Flow<List<PairingInvite>> {
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) return flowOf(emptyList())
        return callbackFlow {
            val registration = firestore.collection(INVITATIONS)
                .whereEqualTo("toEmail", normalized)
                .whereEqualTo("status", STATUS_PENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        // The address itself is deliberately not logged — logcat is
                        // readable by anyone with adb access to the device.
                        Log.w(TAG, "Incoming-invitations listener failed", error)
                    }
                    trySend(snapshot?.documents.orEmpty().mapNotNull { it.toInvite() })
                }
            awaitClose { registration.remove() }
        }
    }

    private suspend fun loadPartner(partnerId: String, pairedAt: Long?): PartnerSummary {
        val data = firestore.collection(USERS).document(partnerId).get().await()
        return PartnerSummary(
            id = partnerId,
            name = data.getString("name").orEmpty(),
            email = data.getString("email").orEmpty(),
            pairedSinceMillis = pairedAt,
            // Written by the co-parent's own `ensureProfile`, so it stays null until their
            // phone runs a build that stores one — the card falls back to the initial.
            // Blank is normalized to null: the legacy full-profile write stores "" for a
            // missing photo, and an empty URL must read as "no photo", not as a broken one.
            photoUrl = data.getString("profilePhotoUrl")?.takeIf { it.isNotBlank() },
            // The co-parent's slot, written by `assignSlots` when the pairing was accepted.
            // Room holds a `users` row for the signed-in user only, so this read is the app's
            // one source for which slot the other parent occupies — every screen that shows
            // their name depends on it. Blank normalizes to null for the same reason as the
            // photo: a pair created before slot assignment shipped has no slot here, and
            // "unknown" must not be dressed up as an answer.
            role = data.getString("role")?.takeIf { it.isNotBlank() },
            // Their answer to "children, pets or both". Absent on a build that never wrote it,
            // which reads as "contributes nothing" rather than "everything" — one parent's real
            // answer must not be widened by the other's silence.
            caresFor = FamilyKind.fromStored(data.getString("caresFor"))
        )
    }

    private suspend fun writeNewInvite(toEmail: String): PairingInvite {
        val user = requireUser()
        val profile = firestore.collection(USERS).document(user.uid).get().await()
        val ttl = if (toEmail.isEmpty()) CODE_TTL_MILLIS else EMAIL_TTL_MILLIS
        val invite = PairingInvite(
            id = UUID.randomUUID().toString(),
            code = InviteCodeGenerator.generate(),
            fromUserId = user.uid,
            fromUserName = profile.getString("name") ?: user.email.orEmpty(),
            fromUserEmail = user.email.orEmpty(),
            toEmail = toEmail,
            expiresAtMillis = System.currentTimeMillis() + ttl
        )
        firestore.collection(INVITATIONS).document(invite.id).set(
            mapOf(
                "id" to invite.id,
                "code" to invite.code,
                "fromUserId" to invite.fromUserId,
                "fromUserName" to invite.fromUserName,
                "fromUserEmail" to invite.fromUserEmail,
                "toEmail" to invite.toEmail,
                "status" to STATUS_PENDING,
                "createdAt" to System.currentTimeMillis(),
                "expiresAt" to invite.expiresAtMillis,
                "acceptedBy" to null
            )
        ).await()
        return invite
    }

    /**
     * Creates the 1:1 conversation after pairing, if one does not already exist for
     * this participant pair.
     *
     * Driven by the observed [PairingState.Paired] transition in [onPairingStateObserved],
     * so both parents get a thread — it used to hang off [redeem]/[acceptIncoming], which
     * only ever run on the accepting device.
     *
     * Best-effort: the pairing itself already succeeded server-side by the time this
     * runs, so a failure here must not surface as a failure of the pairing — it is logged
     * and swallowed instead. This is safe because nothing depends on it having run:
     * `ChatViewModel.startConversationWithPartner` creates the conversation on demand the
     * first time either parent opens chat, through the same idempotent call as here.
     *
     * There is no get-or-create lookup any more. The id is derived from the participant
     * pair, so both devices compute the same one and a duplicate thread is impossible to
     * create — the lookup existed only to prevent duplicates that can no longer happen.
     *
     * [PostPairingConversationSetup] also folds any legacy conversation for this pair into the
     * canonical one right after creating it, inside this same `try` — so a merge failure
     * degrades exactly like a create failure: logged and swallowed, never surfaced as a failed
     * pairing.
     */
    private suspend fun ensureConversationWith(partnerId: String) {
        val uid = authService.getCurrentUser()?.uid ?: return
        if (partnerId.isEmpty()) return
        try {
            // The partner's display name titles the thread; their profile may not carry one
            // yet, in which case a localized placeholder stands in.
            val partnerName = firestore.collection(USERS).document(partnerId).get().await()
                .getString("name")
                .orEmpty()
                .ifEmpty { context.getString(R.string.pairing_default_partner_name) }
            postPairingConversationSetup.run(uid, partnerId, partnerName)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(TAG, "Failed to create the post-pairing conversation with $partnerId", e)
        }
    }

    private fun requireUser() = authService.getCurrentUser()
        ?: throw PairingException(PairingError.Unknown("Not signed in"))

    private fun DocumentSnapshot.toInvite(): PairingInvite? {
        val code = getString("code") ?: return null
        return PairingInvite(
            id = getString("id") ?: id,
            code = code,
            fromUserId = getString("fromUserId").orEmpty(),
            fromUserName = getString("fromUserName").orEmpty(),
            fromUserEmail = getString("fromUserEmail").orEmpty(),
            toEmail = getString("toEmail").orEmpty(),
            expiresAtMillis = getLong("expiresAt") ?: 0L
        )
    }

    /**
     * Narrows a batch of invitation documents down to the code/QR/link invites (as
     * opposed to email invitations) that have not yet expired, newest first.
     *
     * Both [createOrReuseInviteCode]'s reuse lookup and [observeOwnInvites]'s exposed
     * "active invite" slot need exactly this: a single, deterministic answer to "what
     * is this user's current shareable code", even if a stale expired document or a
     * second one from a race was never cleaned up. Filtering and sorting happen
     * client-side (not via a Firestore `orderBy`) so this does not require a composite
     * index that has not been created.
     */
    private fun List<DocumentSnapshot>.toActiveCodeInvites(): List<PairingInvite> {
        val now = System.currentTimeMillis()
        return this
            .filter { it.getString("toEmail").orEmpty().isEmpty() }
            .filter { it.getString("kind") != KIND_GUEST }
            .filter { (it.getLong("expiresAt") ?: 0L) > now }
            .sortedByDescending { it.getLong("createdAt") ?: 0L }
            .mapNotNull { it.toInvite() }
    }

    /**
     * Runs a Firestore block and normalizes any failure into a [PairingException].
     *
     * The lambda is `suspend` because every body calls `await()`; an inline
     * non-suspending version will not compile here. Cancellation must
     * propagate rather than be reported as a pairing failure — otherwise
     * navigating away mid-call surfaces a spurious error instead of the
     * silent cancellation structured concurrency expects.
     */
    private suspend fun <T> runPairing(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: PairingException) {
        Result.failure(e)
    } catch (
        // Firestore failures become a typed error instead of crashing the caller.
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(PairingException(PairingError.Unknown(e.message)))
    }

    private companion object {
        const val TAG = "PairingRepositoryImpl"
        const val USERS = "users"
        const val INVITATIONS = "invitations"
        const val STATUS_PENDING = "pending"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_CANCELLED = "cancelled"
        const val CODE_TTL_MILLIS = 24L * 60 * 60 * 1000
        const val EMAIL_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

        /**
         * Wire value marking a guest invitation, matching `GuestRepositoryImpl.KIND_GUEST`.
         *
         * A co-parent code invite writes no `kind` field at all, so a guest invitation — which
         * also has an empty `toEmail` — is the only pending invite that must be excluded from
         * the "current co-parent code" lookups. Without this filter, `createOrReuseInviteCode`
         * handed a co-parent the grandparent's guest code (which the accept callable then
         * rejects), the pairing screen showed it as "your invite", and `revokeActiveInvite`
         * silently cancelled the outstanding guest invitation too.
         */
        const val KIND_GUEST = "guest"
    }
}
