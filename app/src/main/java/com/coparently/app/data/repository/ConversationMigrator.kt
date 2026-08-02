package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.coparently.app.domain.chat.ConversationKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges a parent pair's legacy, randomly-id'd conversations into the canonical one.
 *
 * Before the deterministic id (`ConversationKey`), each device minted its own
 * `UUID.randomUUID()` conversation the first time it decided one was needed, so a pair's real
 * messages ended up split across two documents that never converged. This migration finds any
 * such leftover conversation for the current pair and folds its messages into the canonical
 * thread, then marks the leftover `archived` so it drops out of
 * [MessageDao.getActiveConversations] for good.
 *
 * **Ordering, and why it is idempotent and safe to interrupt at any point.** For each
 * candidate, every one of its messages is re-pointed *remotely* first; only once all of them
 * have succeeded does the conversation get re-pointed *locally* (a single `UPDATE`, so a
 * message can never end up duplicated under two ids) and archived, on both copies. If the
 * process dies or a remote write fails partway through:
 * - before any remote re-point succeeded: nothing changed anywhere, so the next launch starts
 *   the same candidate over from scratch;
 * - after all remote re-points succeeded but before the local re-point/archive ran: the next
 *   launch re-attempts the remote re-point for the same messages, which is a no-op (Firestore
 *   already has the destination value), then proceeds to the local re-point and archive;
 * - after the local re-point ran but before the archive did: the next launch reads the same
 *   (still unarchived) conversation, finds it has zero messages left under its old id (they
 *   already moved), trivially "succeeds" the remote step over an empty list, repeats the
 *   already-applied local re-point as a no-op, and archives it, completing the interrupted run.
 *
 * If some remote re-points succeed and others fail in the same pass, the conversation is left
 * unarchived and untouched locally, so the whole candidate is retried in full next launch — a
 * message is never re-pointed locally while its remote copy is still split, which is what would
 * make the local device believe the merge finished when the other parent still cannot see it.
 *
 * Once a candidate is fully merged, a second run does nothing at all: it is no longer
 * `archived = false`, so [MessageDao.getActiveConversations] does not return it as a candidate,
 * and the migration makes zero calls for it.
 */
@Singleton
class ConversationMigrator @Inject constructor(
    private val messageDao: MessageDao,
    private val firestoreMessageDataSource: FirestoreMessageDataSource
) {

    /**
     * Merges every legacy conversation between [myUid] and [partnerUid] into the canonical one.
     *
     * Safe to call on every launch: once no legacy conversation remains, this does nothing.
     *
     * @param myUid This device's Firebase uid.
     * @param partnerUid The paired co-parent's Firebase uid.
     */
    suspend fun mergeLegacyConversations(myUid: String, partnerUid: String) {
        val canonicalId = ConversationKey.of(myUid, partnerUid)
        val pair = setOf(myUid, partnerUid)

        val legacyConversations = messageDao.getActiveConversations().first()
            .filter { conversation ->
                // The canonical conversation's own participants are, by definition, this same
                // pair — excluding it by id (not just by "is it archived") is what guarantees
                // it can never be merged into itself.
                conversation.id != canonicalId &&
                    !conversation.archived &&
                    conversation.toDomain().participants.toSet() == pair
            }

        legacyConversations.forEach { legacy -> mergeOneSafely(legacy.id, canonicalId) }
    }

    /**
     * Runs [mergeOne] for a single candidate, containing an unexpected failure (a local Room
     * error, say) to that one candidate.
     *
     * Without this, a pair with more than one legacy conversation — possible across repeated
     * pair/unpair cycles — would have every candidate *after* the failing one silently skipped
     * for the rest of this pass, not just the one that actually failed. [mergeOne] already
     * handles the *expected* remote failure modes internally (a refused or offline re-point,
     * a refused archive); this is the outer net for everything else.
     */
    private suspend fun mergeOneSafely(legacyId: String, canonicalId: String) {
        try {
            mergeOne(legacyId, canonicalId)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(
                TAG,
                "Chat merge: unexpected failure merging $legacyId into $canonicalId; " +
                    "continuing with any other candidates, the next launch retries this one.",
                e
            )
        }
    }

    /**
     * Merges one legacy conversation into [canonicalId], per the ordering documented on the
     * class: remote message re-points must all succeed before anything local or archival
     * happens.
     */
    private suspend fun mergeOne(legacyId: String, canonicalId: String) {
        val messages = messageDao.getMessagesOnce(legacyId)

        val fullyRepointedRemotely = messages.all { message ->
            tryRemoteRepoint(message.id, canonicalId)
        }
        if (!fullyRepointedRemotely) {
            Log.w(
                TAG,
                "Chat merge: not every message in $legacyId reached $canonicalId remotely; " +
                    "leaving $legacyId active so the next launch retries the whole thread."
            )
            return
        }

        messageDao.repointMessages(legacyId, canonicalId)
        archiveLocally(legacyId)
        archiveRemotely(legacyId)

        Log.i(
            TAG,
            "Chat merge: moved ${messages.size} message(s) from $legacyId into $canonicalId " +
                "and archived $legacyId."
        )
    }

    /**
     * Attempts to re-point one message's `conversationId` in Firestore, swallowing a failure.
     *
     * Room is still the source of truth for the local device: a denial or offline device here
     * only means the *other* parent will not see this particular message merged in yet, never
     * that anything is lost — the message keeps existing, unmodified, under its old id.
     *
     * @return `true` if the write reached Firestore, `false` if it was refused or failed.
     */
    private suspend fun tryRemoteRepoint(messageId: String, canonicalId: String): Boolean =
        try {
            firestoreMessageDataSource.repointMessage(messageId, canonicalId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(TAG, "Chat merge: failed to re-point message $messageId to $canonicalId.", e)
            false
        }

    /** Marks the legacy row `archived` locally, idempotently (a no-op if already set). */
    private suspend fun archiveLocally(legacyId: String) {
        val legacy = messageDao.getConversationById(legacyId)?.toDomain() ?: return
        if (legacy.archived) return
        messageDao.insertConversation(legacy.copy(archived = true).toEntity())
    }

    /**
     * Marks the legacy document `archived` remotely, best-effort.
     *
     * A merge into the existing document (see [FirestoreMessageDataSource.setConversation]),
     * so this cannot clobber the legacy conversation's own mark maps — it only ever needs to
     * flip the one field.
     */
    private suspend fun archiveRemotely(legacyId: String) {
        try {
            firestoreMessageDataSource.setConversation(legacyId, mapOf("archived" to true))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(TAG, "Chat merge: failed to mark $legacyId archived remotely.", e)
        }
    }

    private companion object {
        const val TAG = "ConversationMigrator"
    }
}
