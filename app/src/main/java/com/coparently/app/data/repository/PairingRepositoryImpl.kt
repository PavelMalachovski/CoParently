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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
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

    override fun observePairingState(): Flow<PairingState> {
        val user = authService.getCurrentUser() ?: return flowOf(PairingState.Loading)
        return combine(
            observeUserDocument(user.uid),
            observeOwnInvites(user.uid),
            observeIncomingInvites(user.email.orEmpty())
        ) { userSnapshot, own, incoming ->
            val partnerId = userSnapshot?.getString("partnerId").orEmpty()
            if (partnerId.isEmpty()) {
                PairingState.NotPaired(activeInvite = own.firstOrNull(), incoming = incoming)
            } else {
                PairingState.Paired(
                    partner = loadPartner(partnerId, userSnapshot?.getLong("pairedAt"))
                )
            }
        }.distinctUntilChanged()
    }

    override suspend fun createOrReuseInviteCode(): Result<PairingInvite> = runPairing {
        val user = requireUser()
        val existing = firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", user.uid)
            .whereEqualTo("status", STATUS_PENDING)
            .get()
            .await()
            .documents
            .mapNotNull { it.toInvite() }
            .firstOrNull { it.toEmail.isEmpty() && it.expiresAtMillis > System.currentTimeMillis() }

        existing ?: writeNewInvite(toEmail = "")
    }

    override suspend fun revokeActiveInvite(): Result<Unit> = runPairing {
        val user = requireUser()
        firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", user.uid)
            .whereEqualTo("status", STATUS_PENDING)
            .get()
            .await()
            .documents
            .forEach { it.reference.update("status", STATUS_CANCELLED).await() }
    }

    override suspend fun sendEmailInvitation(email: String): Result<Unit> = runPairing {
        writeNewInvite(toEmail = email.trim().lowercase())
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
            .addSnapshotListener { snapshot, _ -> trySend(snapshot) }
        awaitClose { registration.remove() }
    }

    private fun observeOwnInvites(uid: String): Flow<List<PairingInvite>> = callbackFlow {
        val registration = firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", uid)
            .whereEqualTo("status", STATUS_PENDING)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toInvite() })
            }
        awaitClose { registration.remove() }
    }

    private fun observeIncomingInvites(email: String): Flow<List<PairingInvite>> {
        if (email.isEmpty()) return flowOf(emptyList())
        return callbackFlow {
            val registration = firestore.collection(INVITATIONS)
                .whereEqualTo("toEmail", email)
                .whereEqualTo("status", STATUS_PENDING)
                .addSnapshotListener { snapshot, _ ->
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
     * Creates the 1:1 conversation after pairing if it does not exist yet.
     *
     * Runs on whichever device completed the pairing; the other picks the
     * conversation up through the message sync, so both ends get a thread.
     * Best-effort: the pairing itself already succeeded server-side by the
     * time this runs, so a failure here must not surface as a failure of
     * [redeem] or [acceptIncoming] — it is logged and swallowed instead.
     */
    private suspend fun ensureConversationWith(partnerId: String) {
        val uid = authService.getCurrentUser()?.uid ?: return
        if (partnerId.isEmpty()) return
        try {
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

    /**
     * Looks up the partner's display name for the conversation title, bounded
     * by [PARTNER_NAME_LOOKUP_TIMEOUT_MILLIS] so a slow or unreachable read
     * cannot stall the pairing flow that just succeeded — a generic title is
     * used instead when the name is unavailable in time.
     */
    private suspend fun fetchPartnerName(partnerId: String): String {
        val name = withTimeoutOrNull(PARTNER_NAME_LOOKUP_TIMEOUT_MILLIS) {
            firestore.collection(USERS).document(partnerId).get().await().getString("name")
        }
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
        const val PARTNER_NAME_LOOKUP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_PARTNER_NAME = "Co-parent"
    }
}
