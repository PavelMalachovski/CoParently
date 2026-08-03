package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for messages and conversations.
 *
 * Over detekt's `TooManyFunctions` threshold for interfaces, and deliberately so.
 * [getActiveConversations], [getMessagesOnce], [repointMessages] and [archiveConversation] are
 * what the legacy-conversation merge (`ConversationMigrator`) needs; [observeConversationById]
 * is the Room-backed half of the conversation observer. The old `markConversationAsRead` and the
 * unfiltered `getConversationsOrdered` were both removed as read state stopped living in a
 * stored counter and the account-wide conversation list went away with the sync loop.
 * Splitting messages and conversations into two DAOs would satisfy the threshold but is a
 * larger, unrelated refactor of every existing call site.
 */
@Suppress("TooManyFunctions")
@Dao
interface MessageDao {
    // Conversations

    /**
     * Conversations that have not been superseded by a legacy-conversation merge, newest
     * activity first. See [ConversationEntity.archived].
     *
     * The unfiltered `getConversationsOrdered` that used to sit alongside this was removed
     * once nothing called it: the account-wide conversation list disappeared with the sync
     * loop, and the legacy-conversation merge wants the archived rows excluded anyway.
     */
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY lastMessageAtMillis DESC")
    fun getActiveConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    /**
     * Observes one conversation, emitting `null` while no row with [id] exists.
     *
     * The Room-backed half of `MessageRepository.observeConversation`: the remote snapshot
     * listener only mirrors into this table, and what the UI collects comes back out of it,
     * so Room stays the single source of truth for the marks.
     */
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversationById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    /**
     * Flips `archived` to `true` for one conversation and nothing else — used by the
     * legacy-conversation merge.
     *
     * Deliberately a targeted `UPDATE`, not a read-modify-write through [insertConversation].
     * The domain `Conversation`/[ConversationEntity] round trip
     * (`toDomain()`/`toEntity()` in `ChatMappers`) has no field for [ConversationEntity.lastMessageId],
     * so rebuilding the row from the domain model on every archive would silently null it out.
     */
    @Query("UPDATE conversations SET archived = 1 WHERE id = :id")
    suspend fun archiveConversation(id: String)

    // Messages
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAtMillis ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    /** One-shot read of a conversation's messages, oldest first — used by the legacy-conversation merge. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAtMillis ASC")
    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /** Re-points every message of a legacy conversation onto the canonical conversation id. */
    @Query("UPDATE messages SET conversationId = :toConversationId WHERE conversationId = :fromConversationId")
    suspend fun repointMessages(fromConversationId: String, toConversationId: String)

    @Transaction
    suspend fun insertMessageAndUpdateConversation(message: MessageEntity, conversation: ConversationEntity) {
        insertMessage(message)
        insertConversation(conversation)
    }

    @Query("SELECT * FROM messages WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedMessages(): List<MessageEntity>

    @Query("SELECT * FROM conversations WHERE syncedToFirestore = 0")
    suspend fun getUnsyncedConversations(): List<ConversationEntity>
}
