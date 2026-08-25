package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data source for managing messages in Firestore.
 *
 * There is no conversation-*list* accessor here any more. The conversation id is a pure
 * function of the two participants (`ConversationKey`), so a device already knows which
 * document it wants and does not need to query for it — and the list query is what the
 * nested-`collect` sync loop was built on.
 */
@Singleton
class FirestoreMessageDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val conversationsCollection = firestore.collection("conversations")
    private val messagesCollection = firestore.collection("messages")

    /**
     * Observes one conversation document, emitting `null` while it does not exist.
     *
     * Mirrors the [callbackFlow] shape of [getMessages], including closing the flow with the
     * listener's error so the caller's `catch` sees a denied read or a missing document
     * rather than a silent stall.
     *
     * @param conversationId The deterministic conversation id.
     */
    fun observeConversation(conversationId: String): Flow<Map<String, Any>?> = callbackFlow {
        val subscription = conversationsCollection
            .document(conversationId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val data = snapshot?.data?.plus("id" to snapshot.id)
                trySend(data)
            }

        awaitClose { subscription.remove() }
    }

    /**
     * Gets the most recent messages of a conversation as a Flow, oldest of them first.
     *
     * **Bounded to [MESSAGE_WINDOW]** (CQ-6). This listener had no limit at all, so every
     * snapshot carried the entire thread — ten messages a day for three years is about eleven
     * thousand documents, re-delivered on each change, on both parents' phones, for the life of
     * the process. A chat's cost should scale with how much is being said, not with how long
     * the pair has been using the app.
     *
     * `limitToLast` rather than `limit`: the order is ascending, so `limit` would pin the window
     * to the *oldest* messages and a live thread would stop updating the moment it passed the
     * bound. `limitToLast` keeps the newest, which is the window a chat screen is looking at,
     * and Firestore requires the `orderBy` it already has.
     *
     * Nothing is lost on a device that has been running: the mirror writes into Room, Room keeps
     * every message it has ever received, and that is what the UI reads. What does change is a
     * **fresh install**, which now receives the last [MESSAGE_WINDOW] messages of the thread
     * rather than all of them. That is ordinary chat behaviour, and it is a deliberate trade
     * rather than an oversight — but it is a behaviour change, and history older than the window
     * is not fetched by anything else yet.
     */
    fun getMessages(conversationId: String): Flow<List<Map<String, Any>>> = callbackFlow {
        val subscription = messagesCollection
            .whereEqualTo("conversationId", conversationId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .limitToLast(MESSAGE_WINDOW)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val messages = snapshot.documents.map { doc ->
                        doc.data?.plus("id" to doc.id) ?: emptyMap()
                    }
                    trySend(messages)
                }
            }

        awaitClose { subscription.remove() }
    }

    /**
     * Writes one message document, and nothing else.
     *
     * Nothing about the conversation is touched here — [bumpLastMessageAt] does that, and the
     * split is deliberate; see its KDoc. Nor is the message copied into a `lastMessage` field
     * on the conversation, as an older version did: that doubled every write, duplicated what
     * the `messages` collection already holds, and gave the security rules a second place to
     * police the same data. The domain object still exposes `lastMessage`; it is derived
     * locally from the newest message in the thread.
     *
     * @param messageId Document id of the message.
     * @param messageData The message document.
     */
    suspend fun sendMessage(messageId: String, messageData: Map<String, Any>) {
        messagesCollection.document(messageId).set(messageData).await()
    }

    /**
     * Advances a conversation's ordering timestamp.
     *
     * Separate from [sendMessage] on purpose. The two used to be one awaited pair, so a
     * successful message write followed by a failed `lastMessageAt` bump threw, and the caller
     * marked a message the co-parent had already received as failed to send. The message is the
     * payload; this is metadata, and it must not be able to condemn the payload.
     *
     * @param conversationId The thread to bump.
     * @param lastMessageAtMillis The newest message's timestamp as epoch millis.
     */
    suspend fun bumpLastMessageAt(conversationId: String, lastMessageAtMillis: Long) {
        conversationsCollection.document(conversationId)
            .update("lastMessageAt", lastMessageAtMillis)
            .await()
    }

    /**
     * Creates or amends a conversation document, **merging** into whatever is already there.
     *
     * A plain `set` would replace the document, dropping both mark maps of whichever device
     * happened to write second — and this runs on every pairing and every chat open, so that
     * would be a routine loss rather than an edge case.
     *
     * @param conversationId The deterministic conversation id.
     * @param conversationData The fields to write.
     */
    suspend fun setConversation(conversationId: String, conversationData: Map<String, Any>) {
        conversationsCollection.document(conversationId)
            .set(conversationData, SetOptions.merge())
            .await()
    }

    /**
     * Records that [userId] has read the thread up to [atMillis].
     *
     * A single dotted-path update, so it touches only this user's key and satisfies the
     * `conversations` rule that permits amending your own mark and nothing else.
     *
     * @param conversationId The deterministic conversation id.
     * @param userId The uid whose mark advances.
     * @param atMillis The mark, as epoch millis.
     */
    suspend fun markRead(conversationId: String, userId: String, atMillis: Long) {
        conversationsCollection.document(conversationId)
            .update("lastReadAt.$userId", atMillis)
            .await()
    }

    /**
     * Records that [userId]'s device has ingested messages up to [atMillis].
     *
     * @param conversationId The deterministic conversation id.
     * @param userId The uid whose mark advances.
     * @param atMillis The mark, as epoch millis.
     */
    suspend fun markDelivered(conversationId: String, userId: String, atMillis: Long) {
        conversationsCollection.document(conversationId)
            .update("lastDeliveredAt.$userId", atMillis)
            .await()
    }

    /**
     * Moves one message onto a different conversation, as the legacy-conversation merge does.
     *
     * A single-field update, matching the deployed `messages` rule: a participant of the
     * message's current conversation may change `conversationId` to the canonical conversation
     * for that conversation's participants, and nothing else in the same write — never to an
     * arbitrary conversation, even a same-participants one (see `canRepointMessage` in
     * `firestore.rules`; a looser, participant-based version of this rule was found to let one
     * parent permanently hide the other's message in a conversation the other never observes).
     * Setting it to a value it already has is idempotent and denied by nothing, which is what
     * makes a retried merge safe.
     *
     * @param messageId Document id of the message; unchanged by the move.
     * @param toConversationId The conversation the message now belongs to — must be the
     *   canonical conversation for the message's current participants, or the write is denied.
     */
    suspend fun repointMessage(messageId: String, toConversationId: String) {
        messagesCollection.document(messageId)
            .update("conversationId", toConversationId)
            .await()
    }

    /**
     * The ids of every message currently filed under [conversationId], read once from the
     * **server** — not a live listener, and never from the offline cache.
     *
     * Used by the legacy-conversation merge to find messages Room does not know about: nothing
     * else ever points a listener at a legacy conversation id, so a message that reached
     * Firestore under one but never made it into this device's Room would otherwise never be
     * discovered, re-pointed, or protected from being stranded when the legacy conversation is
     * archived away.
     *
     * [Source.SERVER] is load-bearing, not a preference. With offline persistence on, a
     * `Source.DEFAULT` one-shot read may be answered from the local cache — and because nothing
     * ever listens on a legacy conversation id, that cache holds no legacy messages at all. The
     * read would then succeed with an *empty* result, the caller would union it with Room's rows,
     * conclude that Room already knows everything, and archive the legacy conversation, stranding
     * exactly the remote-only message this read exists to find. `Source.SERVER` cannot answer
     * from the cache: offline, it fails instead, and a failure is unambiguous.
     *
     * That is why this throws rather than returning an empty set on failure: an empty set means
     * "the server says there are none", and nothing else. `ConversationMigrator.collectMessageIds`
     * turns any exception here into `null` — "the message set is unknown" — which skips the
     * candidate for this pass and retries it on the next launch. An offline device therefore
     * postpones the merge instead of completing it on a half-known list.
     *
     * @param conversationId The (legacy) conversation to read.
     * @return The ids of its messages, as the server holds them; empty only if there are none.
     * @throws com.google.firebase.firestore.FirebaseFirestoreException if the server cannot be
     *   reached (the device is offline) or the read is denied.
     */
    suspend fun fetchMessageIds(conversationId: String): Set<String> =
        messagesCollection
            .whereEqualTo("conversationId", conversationId)
            .get(Source.SERVER)
            .await()
            .documents
            .map { it.id }
            .toSet()

    private companion object {
        /**
         * How many of a thread's newest messages the live listener carries.
         *
         * Large enough that scrolling a normal conversation never reaches the edge, small enough
         * that the per-snapshot cost stops growing with the pair's tenure. It bounds the network
         * and the mirror, not what the device knows: Room keeps everything it has ever received.
         */
        const val MESSAGE_WINDOW = 200L
    }
}
