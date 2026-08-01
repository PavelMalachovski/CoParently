package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.util.UUID
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
    private val messageRepository: MessageRepository
) : PairingRepository {

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
            .forEach { it.reference.update("status", STATUS_CANCELLED).await() }
    }

    override suspend fun sendEmailInvitation(email: String): Result<Unit> {
        val normalized = email.trim().lowercase()
        if (!EMAIL_REGEX.matches(normalized)) {
            return Result.failure(PairingException(PairingError.Unknown("Invalid email address")))
        }
        return runPairing { writeNewInvite(toEmail = normalized) }
    }

    override suspend fun redeem(code: String): Result<Unit> {
        val normalized = code.trim().uppercase()
        if (!InviteCodeGenerator.isValid(normalized)) {
            return Result.failure(PairingException(PairingError.NotFound))
        }
        return pairingFunctions.acceptInvitation(code = normalized)
            .onSuccess { partnerId -> ensureConversationWith(partnerId) }
            .map { }
    }

    override suspend fun acceptIncoming(invitationId: String): Result<Unit> =
        pairingFunctions.acceptInvitation(invitationId = invitationId)
            .onSuccess { partnerId -> ensureConversationWith(partnerId) }
            .map { }

    override suspend fun rejectIncoming(invitationId: String): Result<Unit> = runPairing {
        firestore.collection(INVITATIONS).document(invitationId)
            .update("status", STATUS_REJECTED)
            .await()
    }

    override suspend fun unpair(): Result<Unit> = pairingFunctions.unpair().map { }

    // ---- Firestore plumbing -------------------------------------------

    private fun observeUserDocument(uid: String): Flow<DocumentSnapshot?> = callbackFlow {
        val registration = firestore.collection(USERS).document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User document listener failed for uid=$uid", error)
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
                        Log.w(TAG, "Incoming-invitations listener failed for email=$normalized", error)
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
            pairedSinceMillis = pairedAt
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
     * Best-effort: the pairing itself already succeeded server-side by the time this
     * runs, so a failure here must not surface as a failure of [redeem] or
     * [acceptIncoming] — it is logged and swallowed instead. This is safe because
     * nothing depends on it having run: `ChatViewModel.startConversationWithPartner`
     * creates the conversation on demand the first time either parent opens chat, using
     * the same participant-pair lookup as here.
     */
    private suspend fun ensureConversationWith(partnerId: String) {
        val uid = authService.getCurrentUser()?.uid ?: return
        if (partnerId.isEmpty()) return
        try {
            val pair = setOf(uid, partnerId)
            val alreadyExists = messageRepository.getConversations(uid).first().any {
                it.participants.toSet() == pair
            }
            if (alreadyExists) return
            messageRepository.createConversation(
                Conversation(
                    id = UUID.randomUUID().toString(),
                    participants = listOf(uid, partnerId),
                    title = fetchPartnerName(partnerId),
                    createdAt = LocalDateTime.now()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(TAG, "Failed to create the post-pairing conversation with $partnerId", e)
        }
    }

    /** Looks up the partner's display name for the conversation title. */
    private suspend fun fetchPartnerName(partnerId: String): String {
        val name = firestore.collection(USERS).document(partnerId).get().await().getString("name")
        return name.orEmpty().ifEmpty { DEFAULT_PARTNER_NAME }
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
        const val DEFAULT_PARTNER_NAME = "Co-parent"
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
