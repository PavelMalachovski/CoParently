package com.coparently.app.domain.repository

import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing messages and conversations.
 * Part of the domain layer in Clean Architecture.
 *
 * The surface is per-conversation rather than per-account. The conversation id is derived
 * from the two participants (`com.coparently.app.domain.chat.ConversationKey`), so a caller
 * always knows which thread it wants and there is no list to traverse — which is what let
 * the previous `syncWithFirestore` be deleted rather than untangled. It collected an
 * infinite conversation-snapshot flow and, inside that collector, an infinite message
 * flow; the inner one never returned, so the outer one never advanced past its first
 * emission and the two phones never converged.
 *
 * Every observer here is Room-backed: the remote listener only mirrors into Room, and what
 * the caller collects comes out of Room. A remote failure therefore degrades to "no new
 * data this round", never to an empty screen or a crash.
 */
interface MessageRepository {
    /**
     * Observes one conversation, emitting `null` until it exists locally.
     *
     * @param conversationId The deterministic conversation id.
     */
    fun observeConversation(conversationId: String): Flow<Conversation?>

    /**
     * Observes the messages of one conversation, oldest first.
     *
     * @param conversationId The deterministic conversation id.
     */
    fun observeMessages(conversationId: String): Flow<List<Message>>

    /**
     * Observes how many of the co-parent's messages [myUid] has not read, as a live count.
     *
     * The answer [observeMessages] plus `ChatReadState.unreadCount` would give — computed in
     * SQL instead, so a caller that only wants the number does not materialise the thread to
     * get it. That difference is the whole reason this exists: after three years of ten
     * messages a day, rendering one integer on the home screen meant mapping about eleven
     * thousand rows into domain objects on every emission.
     *
     * **Room only.** Nothing here subscribes to the remote listener, so the count reflects
     * what has been mirrored into Room rather than opening a second listener of its own. The
     * badge stays live because `ChatViewModel.unreadCount` — held for the process lifetime by
     * `NavGraph.rememberChatUnreadCount()` — keeps the mirror running. Do not convert *that*
     * one to this without replacing what keeps the mirror alive; it is the same entanglement
     * CQ-8 describes, from the other side.
     *
     * @param conversationId The deterministic conversation id.
     * @param myUid The reader — their own messages never count as unread.
     */
    fun observeUnreadCount(conversationId: String, myUid: String): Flow<Int>

    /**
     * Creates the 1:1 conversation between [myUid] and [partnerUid] if it does not exist,
     * and returns its id.
     *
     * Idempotent by construction: the id is derived from the participant pair and the
     * remote write merges, so running it again — on either device, any number of times —
     * can neither produce a second thread nor clobber marks already recorded on this one.
     *
     * @param myUid This device's signed-in uid.
     * @param partnerUid The co-parent's uid.
     * @param title Display title for the thread, normally the co-parent's name.
     * @return The conversation id.
     * @throws IllegalArgumentException if the two uids cannot form a conversation key.
     */
    suspend fun ensureConversation(myUid: String, partnerUid: String, title: String): String

    /**
     * Sends a message, storing it locally first and settling its status on the outcome.
     *
     * @param message The message to send.
     */
    suspend fun sendMessage(message: Message)

    /**
     * Records that [myUid] has read [conversationId] up to now.
     *
     * @param conversationId The deterministic conversation id.
     * @param myUid This device's signed-in uid.
     */
    /**
     * Re-sends every conversation and message this device wrote locally but never got onto the
     * server.
     *
     * Safe to call at any time and cheap when there is nothing queued. One pass, no loop: the
     * next thread open, pull-to-refresh, or periodic sync is the retry.
     */
    suspend fun flushOutbox()

    suspend fun markRead(conversationId: String, myUid: String)

    /**
     * Records that [myUid]'s device has ingested [conversationId] up to now.
     *
     * @param conversationId The deterministic conversation id.
     * @param myUid This device's signed-in uid.
     */
    suspend fun markDelivered(conversationId: String, myUid: String)

    /**
     * Deletes a message locally.
     *
     * @param messageId The message id.
     */
    suspend fun deleteMessage(messageId: String)
}
