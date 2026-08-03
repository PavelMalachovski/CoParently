package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.coparently.app.domain.chat.ChatReadState
import com.coparently.app.domain.chat.ConversationKey
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [MessageRepository].
 *
 * Two independent realtime observers, neither nested inside the other: one for the
 * conversation document, one for its messages. The predecessor collected an infinite
 * conversation-snapshot flow and started a second infinite message flow *inside* that
 * collector, so the inner collect never returned and the outer one never advanced past its
 * first emission — which is why the two parents' threads never converged.
 *
 * Each observer mirrors what arrives into Room and hands the caller the Room-backed flow.
 * Room is the source of truth: a denied read, a missing index or a cold offline cache
 * degrades to "nothing new this round", logged, with the local copy intact.
 */
@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreMessageDataSource: FirestoreMessageDataSource
) : MessageRepository {

    /**
     * Observes the conversation document.
     *
     * The remote branch never emits downstream — see [mirrorOnly]. It exists only to fold
     * the snapshot into Room, which then drives the flow the caller actually collects.
     */
    override fun observeConversation(conversationId: String): Flow<Conversation?> {
        val mirror = firestoreMessageDataSource.observeConversation(conversationId)
            .onEach { remote -> mirrorConversation(conversationId, remote) }
            .catch { e ->
                Log.w(
                    TAG,
                    "Conversation observe failed for conversationId=$conversationId " +
                        "(conversations/$conversationId document listener). " +
                        "This is a single-document listener, so no index is involved — a " +
                        "PERMISSION_DENIED here means the deployed conversations rule, " +
                        "check firestore.rules. Keeping the local Room copy.",
                    e
                )
            }
            .mirrorOnly<Conversation?>()

        val local = messageDao.observeConversationById(conversationId)
            .map { entity -> entity?.toDomain() }

        return merge(mirror, local)
    }

    /**
     * Observes the conversation's messages, oldest first.
     *
     * The `messages` query is `conversationId == … ORDER BY timestamp ASC`, which Firestore
     * can only serve from a composite index. When that index is absent (or still building,
     * or the read is denied, or the device is offline with a cold cache) the snapshot
     * listener reports `FAILED_PRECONDITION` and the flow fails. Without the [catch] below
     * that failure escapes into the caller's `viewModelScope.launch`, where nothing handles
     * it and the process is killed — which is exactly what used to happen on opening Chat.
     */
    override fun observeMessages(conversationId: String): Flow<List<Message>> {
        val mirror = firestoreMessageDataSource.getMessages(conversationId)
            .onEach { documents -> mirrorMessages(documents) }
            .catch { e ->
                Log.w(
                    TAG,
                    "Message observe failed for conversationId=$conversationId " +
                        "(messages: conversationId ==, orderBy timestamp ASC). " +
                        "A FAILED_PRECONDITION here means a missing Firestore index — " +
                        "check firestore.indexes.json. Keeping the local Room copy.",
                    e
                )
            }
            .mirrorOnly<List<Message>>()

        val local = messageDao.getMessages(conversationId)
            .map { entities -> entities.map { it.toDomain() } }

        return merge(mirror, local)
    }

    override suspend fun ensureConversation(myUid: String, partnerUid: String, title: String): String {
        val conversationId = ConversationKey.of(myUid, partnerUid)
        val participants = listOf(myUid, partnerUid).sorted()
        val existing = messageDao.getConversationById(conversationId)?.toDomain()

        // A `copy` of the existing row, never a fresh object: rebuilding one here would put
        // the mark maps back to their defaults on every pairing and every chat open, which
        // is the reset the deleted sync loop used to perform.
        val conversation = existing?.copy(
            participants = participants,
            title = title.ifBlank { existing.title }
        ) ?: Conversation(
            id = conversationId,
            participants = participants,
            title = title,
            createdAt = LocalDateTime.now()
        )
        messageDao.insertConversation(conversation.toEntity())

        // `title` is deliberately absent from the shared document. It is *this* device's
        // name for the other parent, so writing it to a document both parents read made each
        // of them relabel the other's thread with their own name on every open, flip-flopping
        // forever. Each device derives its own title from the partner's profile instead.
        runRemote("ensureConversation", conversationId) {
            firestoreMessageDataSource.setConversation(
                conversationId,
                mapOf(
                    "id" to conversationId,
                    "participants" to participants,
                    "archived" to conversation.archived,
                    "createdAt" to conversation.createdAt.toIsoString()
                )
            )
            messageDao.insertConversation(conversation.copy(syncedToFirestore = true).toEntity())
        }
        return conversationId
    }

    override suspend fun sendMessage(message: Message) {
        // Store it as SENDING first, so the thread shows it immediately and a crash between
        // here and the remote write cannot lose it.
        val sendingMessage = if (message.status == MessageSendStatus.SENT) {
            message.copy(status = MessageSendStatus.SENDING)
        } else {
            message
        }
        messageDao.insertMessage(sendingMessage.toEntity())

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser == null) {
            messageDao.insertMessage(message.copy(status = MessageSendStatus.ERROR).toEntity())
            return
        }

        val sentAtMillis = message.sentAtMillis
        try {
            firestoreMessageDataSource.sendMessage(
                message.id,
                message.toFirestoreMap(),
                sentAtMillis
            )
            messageDao.insertMessage(
                message.copy(syncedToFirestore = true, status = MessageSendStatus.SENT).toEntity()
            )
            bumpConversation(message.conversationId, message.senderId, sentAtMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            messageDao.insertMessage(message.copy(status = MessageSendStatus.ERROR).toEntity())
            throw e
        }
    }

    override suspend fun markRead(conversationId: String, myUid: String) {
        val atMillis = newestMessageMillis(conversationId) ?: return
        updateConversation(conversationId) { conversation ->
            conversation.copy(
                lastReadAt = ChatReadState.advancedMark(conversation.lastReadAt, myUid, atMillis)
            )
        }
        runRemote("markRead", conversationId) {
            firestoreMessageDataSource.markRead(conversationId, myUid, atMillis)
        }
    }

    override suspend fun markDelivered(conversationId: String, myUid: String) {
        val atMillis = newestMessageMillis(conversationId) ?: return
        updateConversation(conversationId) { conversation ->
            conversation.copy(
                lastDeliveredAt = ChatReadState.advancedMark(conversation.lastDeliveredAt, myUid, atMillis)
            )
        }
        runRemote("markDelivered", conversationId) {
            firestoreMessageDataSource.markDelivered(conversationId, myUid, atMillis)
        }
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
        // Note: Deleting from Firestore is not implemented in this version for safety
    }

    // ---- mirroring --------------------------------------------------------

    /**
     * Folds the remote conversation document into the local row.
     *
     * Deliberately a *merge*, not a replace. The remote copy is partial by nature — an older
     * document predates the mark maps, and a mark this device has just written may not have
     * round-tripped yet — so replacing the row from it would silently reset read state on
     * every Chat open. Both marks are monotonic epoch timestamps, so taking the larger of
     * the two per uid is both safe and correct: a mark can only ever move forward.
     *
     * @param conversationId The conversation being observed.
     * @param remote The remote document, or `null` while it does not exist.
     */
    private suspend fun mirrorConversation(conversationId: String, remote: Map<String, Any>?) {
        if (remote == null) return
        val local = messageDao.getConversationById(conversationId)?.toDomain()

        val merged = Conversation(
            id = conversationId,
            participants = remote.stringList("participants").ifEmpty { local?.participants.orEmpty() },
            // Local first: the title is this device's name for the other parent and is not
            // shared. The remote value is only a fallback for a document written before that
            // was true, and for a device that has no local row yet.
            title = local?.title?.takeIf { it.isNotBlank() }
                ?: (remote["title"] as? String).orEmpty(),
            lastReadAt = ChatReadState.mergeMarks(local?.lastReadAt, remote.markMap("lastReadAt")),
            lastDeliveredAt = ChatReadState.mergeMarks(
                local?.lastDeliveredAt,
                remote.markMap("lastDeliveredAt")
            ),
            lastMessageAtMillis = maxOfNullable(local?.lastMessageAtMillis, remote.longOrNull("lastMessageAt")),
            // Archiving is one-way: once a legacy thread has been merged away it stays merged,
            // whichever copy learns about it first.
            archived = (remote["archived"] as? Boolean ?: false) || (local?.archived ?: false),
            createdAt = remote.dateTimeOrNull("createdAt") ?: local?.createdAt ?: LocalDateTime.now(),
            syncedToFirestore = true
        )
        // Firestore echoes back every write this device makes, including its own marks, so
        // most snapshots carry nothing new. Writing the row anyway would tick Room's
        // invalidation tracker and re-emit to every observer for no reason.
        if (merged == local) return
        messageDao.insertConversation(merged.toEntity())
    }

    /**
     * Folds a batch of remote message documents into Room.
     *
     * Everything that arrives this way is [MessageSendStatus.SENT] — the row records what
     * *this* device's own write achieved. `DELIVERED` and `READ` describe the other parent
     * and are derived at render time from the conversation's marks; persisting either would
     * let a stale row outrank the live marks.
     */
    private suspend fun mirrorMessages(documents: List<Map<String, Any>>) {
        documents.forEach { data ->
            val message = data.toMessageOrNull() ?: return@forEach
            messageDao.insertMessage(message.toEntity())
        }
    }

    // ---- local writes -----------------------------------------------------

    /**
     * Applies [transform] to the local conversation row, if there is one.
     *
     * Always a read-modify-write of the *whole local row*, so a single-field change can
     * never drop the fields it did not touch.
     */
    private suspend fun updateConversation(
        conversationId: String,
        transform: (Conversation) -> Conversation
    ) {
        val local = messageDao.getConversationById(conversationId)?.toDomain() ?: return
        val updated = transform(local)
        if (updated == local) return
        messageDao.insertConversation(updated.toEntity())
    }

    /**
     * The timestamp of the newest message this device holds for [conversationId], or `null`
     * when the thread is empty.
     *
     * This — not the wall clock — is what a mark is set to. "Read up to here" is a statement
     * about a position in the thread, and it is compared against message timestamps by
     * `ChatReadState`, so taking it from the reader's clock mixed two different clocks into
     * one comparison. A device whose clock was briefly set forward would write a far-future
     * mark that the monotonic merge then preserved forever, permanently zeroing that user's
     * unread count with no way back. A message-derived mark cannot outrun the thread.
     *
     * `maxOf` rather than "the last row", so the value does not depend on the query's
     * ordering staying what it is today.
     */
    private suspend fun newestMessageMillis(conversationId: String): Long? =
        messageDao.getMessagesOnce(conversationId).maxOfOrNull { it.sentAtMillis }

    /**
     * Advances the conversation's ordering timestamp after [senderId] sent a message.
     *
     * The sender's own read mark moves with it: a message can only be sent from inside the
     * open thread, so the sender has by definition read everything up to it. Without this
     * the sender's own message would light up the unread indicator on their own conversation
     * row the moment the remote echo arrived.
     */
    private suspend fun bumpConversation(conversationId: String, senderId: String, atMillis: Long) {
        updateConversation(conversationId) { conversation ->
            conversation.copy(
                lastMessageAtMillis = maxOfNullable(conversation.lastMessageAtMillis, atMillis),
                lastReadAt = ChatReadState.advancedMark(conversation.lastReadAt, senderId, atMillis)
            )
        }
    }

    /**
     * Runs a remote write, swallowing a failure with a log.
     *
     * Room already holds the change by the time this runs, so a refused or unreachable write
     * degrades to "local for now, retried on the next open" rather than to an exception in
     * the caller's coroutine. Cancellation is rethrown — it is not a failure.
     *
     * @param operation Short description, used as the log context.
     * @param conversationId The conversation the write targets.
     * @param block The remote write.
     */
    private suspend fun runRemote(
        operation: String,
        conversationId: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(
                TAG,
                "Chat $operation failed for conversationId=$conversationId. " +
                    "Room keeps the local copy and the next open retries.",
                e
            )
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Turns a mirroring flow into one that never emits.
     *
     * Lets the remote listener be [merge]d with the Room-backed flow purely for its side
     * effect, so exactly one of the two branches — Room — decides what the caller sees.
     */
    private fun <T> Flow<*>.mirrorOnly(): Flow<T> = transform { }

    private companion object {
        const val TAG = "MessageRepo"
    }
}
