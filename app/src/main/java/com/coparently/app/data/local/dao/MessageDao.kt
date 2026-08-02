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
 * Over detekt's `TooManyFunctions` threshold by one, and deliberately so: this task added
 * [getConversationsOrdered] (a corrected replacement for the previous, nonsensically-ordered
 * query), [getActiveConversations], [getMessagesOnce] and [repointMessages] — the last three
 * are what the legacy-conversation merge (a later task) needs. The old `markConversationAsRead`
 * was removed in the same change since read state no longer lives in a stored counter.
 * Splitting messages and conversations into two DAOs would satisfy the threshold but is a
 * larger, unrelated refactor of every existing call site.
 */
@Suppress("TooManyFunctions")
@Dao
interface MessageDao {
    // Conversations

    /**
     * All conversations, newest activity first.
     *
     * Replaces the previous `ORDER BY lastMessageId DESC`, which sorted by an id string and
     * not by time. [lastMessageAtMillis] is the actual ordering timestamp.
     */
    @Query("SELECT * FROM conversations ORDER BY lastMessageAtMillis DESC")
    fun getConversationsOrdered(): Flow<List<ConversationEntity>>

    /**
     * Conversations that have not been superseded by a legacy-conversation merge, newest
     * activity first. See [ConversationEntity.archived].
     */
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY lastMessageAtMillis DESC")
    fun getActiveConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    // Messages
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    /** One-shot read of a conversation's messages, oldest first — used by the legacy-conversation merge. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
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
