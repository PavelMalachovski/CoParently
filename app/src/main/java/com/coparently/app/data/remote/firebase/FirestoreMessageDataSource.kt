package com.coparently.app.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
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
     * Gets messages for a conversation as a Flow.
     */
    fun getMessages(conversationId: String): Flow<List<Map<String, Any>>> = callbackFlow {
        val subscription = messagesCollection
            .whereEqualTo("conversationId", conversationId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
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
     * Writes a message and advances its conversation's ordering timestamp.
     *
     * Only `lastMessageAt` is written back onto the conversation. The previous version
     * copied the whole message map into a `lastMessage` field, which doubled every write,
     * duplicated content the `messages` collection already holds, and gave the security
     * rules a second place to police the same data. The domain object still exposes
     * `lastMessage`; it is derived locally from the newest message in the thread.
     *
     * @param messageId Document id of the message.
     * @param messageData The message document.
     * @param lastMessageAtMillis The message's timestamp as epoch millis, for ordering.
     */
    suspend fun sendMessage(
        messageId: String,
        messageData: Map<String, Any>,
        lastMessageAtMillis: Long
    ) {
        messagesCollection.document(messageId).set(messageData).await()

        val conversationId = messageData["conversationId"] as String
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
     * The ids of every message currently filed under [conversationId], read once — not a live
     * listener.
     *
     * Used by the legacy-conversation merge to find messages Room does not know about: nothing
     * else ever points a listener at a legacy conversation id, so a message that reached
     * Firestore under one but never made it into this device's Room would otherwise never be
     * discovered, re-pointed, or protected from being stranded when the legacy conversation is
     * archived away.
     *
     * @param conversationId The (legacy) conversation to read.
     * @return The ids of its messages; empty if there are none or the read is denied.
     */
    suspend fun fetchMessageIds(conversationId: String): Set<String> =
        messagesCollection
            .whereEqualTo("conversationId", conversationId)
            .get()
            .await()
            .documents
            .map { it.id }
            .toSet()
}
