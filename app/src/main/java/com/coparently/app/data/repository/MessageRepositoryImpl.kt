package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.MessageDao
import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreMessageDataSource
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.model.MessageType
import com.coparently.app.domain.repository.MessageRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreMessageDataSource: FirestoreMessageDataSource
) : MessageRepository {

    private val gson = Gson()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getConversations(userId: String): Flow<List<Conversation>> {
        return messageDao.getConversationsOrdered().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessages(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getConversationById(id: String): Conversation? {
        return messageDao.getConversationById(id)?.toDomain()
    }

    override suspend fun sendMessage(message: Message) {
        // Insert message with SENDING status
        val sendingMessage = if (message.status == MessageSendStatus.SENT) {
            message.copy(status = MessageSendStatus.SENDING)
        } else {
            message
        }
        val entity = sendingMessage.toEntity()
        messageDao.insertMessage(entity)

        // Sync to Firestore
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            try {
                val messageData = mapOf(
                    "id" to message.id,
                    "conversationId" to message.conversationId,
                    "senderId" to message.senderId,
                    "senderName" to message.senderName,
                    "content" to message.content,
                    "timestamp" to message.timestamp.format(dateFormatter),
                    "messageType" to message.messageType.name,
                    "attachments" to message.attachments,
                    "isRead" to message.isRead,
                    "replyToMessageId" to (message.replyToMessageId ?: "")
                )
                firestoreMessageDataSource.sendMessage(message.id, messageData)

                // Mark as synced and SENT
                val syncedMessage = message.copy(
                    syncedToFirestore = true,
                    status = MessageSendStatus.SENT
                )
                messageDao.insertMessage(syncedMessage.toEntity())
            } catch (e: Exception) {
                // Mark as ERROR on failure
                val errorMessage = message.copy(
                    status = MessageSendStatus.ERROR
                )
                messageDao.insertMessage(errorMessage.toEntity())
                throw e
            }
        } else {
            // No user, mark as ERROR
            val errorMessage = message.copy(
                status = MessageSendStatus.ERROR
            )
            messageDao.insertMessage(errorMessage.toEntity())
        }
    }

    /**
     * Marks all messages in a conversation as read for [userId].
     *
     * There is no stored counter to zero any more — read state now lives on
     * [Conversation.lastReadAt] and is derived by `ChatReadState`. Writing that mark to Room
     * and Firestore is a later task's job (the conversation-observer rework); today this only
     * notifies Firestore, which is itself still a no-op there.
     */
    override suspend fun markAsRead(conversationId: String, userId: String) {
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            firestoreMessageDataSource.markAsRead(conversationId, userId)
        }
    }

    override suspend fun createConversation(conversation: Conversation) {
        val entity = conversation.toEntity()
        messageDao.insertConversation(entity)

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            val conversationData = mapOf(
                "id" to conversation.id,
                "participants" to conversation.participants,
                "title" to conversation.title,
                "createdAt" to conversation.createdAt.format(dateFormatter)
            )
            firestoreMessageDataSource.setConversation(conversation.id, conversationData)

            val syncedConversation = conversation.copy(syncedToFirestore = true)
            messageDao.insertConversation(syncedConversation.toEntity())
        }
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
        // Note: Deleting from Firestore is not implemented in this version for safety
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        // Sync conversations. Offline-first: a Firestore failure (denied read, missing
        // index, no network) must not crash the app — Room stays the source of truth.
        firestoreMessageDataSource.getConversations(firebaseUser.uid)
            .catch { e ->
                android.util.Log.w(
                    TAG,
                    "Conversation sync failed for uid=${firebaseUser.uid} " +
                        "(conversations: array-contains participants). " +
                        "A FAILED_PRECONDITION here means a missing Firestore index — " +
                        "check firestore.indexes.json. Keeping the local Room copy.",
                    e
                )
            }
            .collect { conversations ->
                conversations.forEach { data ->
                    val conversation = Conversation(
                        id = data["id"] as String,
                        participants = (data["participants"] as? List<String>) ?: emptyList(),
                        title = data["title"] as String,
                        createdAt = LocalDateTime.parse(data["createdAt"] as String, dateFormatter),
                        syncedToFirestore = true
                    )
                    messageDao.insertConversation(conversation.toEntity())

                    // Sync messages for this conversation
                    syncMessagesForConversation(conversation.id)
                }
            }
    }

    /**
     * Mirrors the remote messages of [conversationId] into Room.
     *
     * The `messages` query is `conversationId == … ORDER BY timestamp ASC`, which Firestore can
     * only serve from a composite index. When that index is absent (or still building, or the
     * read is denied, or the device is offline with a cold cache) the snapshot listener reports
     * `FAILED_PRECONDITION` and the flow fails. Without the [catch] below that failure escaped
     * this collector, propagated out of [syncWithFirestore] into `ChatViewModel`'s
     * `viewModelScope.launch`, and killed the process every time the user opened Chat.
     *
     * Offline-first: Room is the source of truth, so a failed remote read degrades to "no new
     * messages this round" rather than to a crash. It is logged, never swallowed silently —
     * the message names the query so a missing index is recognisable in logcat.
     */
    private suspend fun syncMessagesForConversation(conversationId: String) {
        firestoreMessageDataSource.getMessages(conversationId)
            .catch { e ->
                android.util.Log.w(
                    TAG,
                    "Message sync failed for conversationId=$conversationId " +
                        "(messages: conversationId ==, orderBy timestamp ASC). " +
                        "A FAILED_PRECONDITION here means a missing Firestore index — " +
                        "check firestore.indexes.json. Keeping the local Room copy.",
                    e
                )
            }
            .collect { messages ->
                messages.forEach { data ->
                    val message = Message(
                        id = data["id"] as String,
                        conversationId = data["conversationId"] as String,
                        senderId = data["senderId"] as String,
                        senderName = data["senderName"] as String,
                        content = data["content"] as String,
                        timestamp = LocalDateTime.parse(data["timestamp"] as String, dateFormatter),
                        messageType = MessageType.valueOf(data["messageType"] as String),
                        attachments = (data["attachments"] as? List<String>) ?: emptyList(),
                        isRead = (data["isRead"] as? Boolean) ?: false,
                        replyToMessageId = data["replyToMessageId"] as? String,
                        syncedToFirestore = true,
                        status = MessageSendStatus.SENT // Messages from Firestore are always SENT
                    )
                    messageDao.insertMessage(message.toEntity())
                }
            }
    }

    private fun ConversationEntity.toDomain(): Conversation {
        val participantsListType = object : TypeToken<List<String>>() {}.type
        val participants: List<String> = gson.fromJson(participantsJson, participantsListType)
        val marksType = object : TypeToken<Map<String, Long>>() {}.type

        return Conversation(
            id = id,
            participants = participants,
            title = title,
            lastReadAt = gson.fromJson(lastReadAtJson, marksType) ?: emptyMap(),
            lastDeliveredAt = gson.fromJson(lastDeliveredAtJson, marksType) ?: emptyMap(),
            lastMessageAtMillis = lastMessageAtMillis,
            archived = archived,
            createdAt = createdAt,
            syncedToFirestore = syncedToFirestore
        )
    }

    private fun Conversation.toEntity(): ConversationEntity {
        return ConversationEntity(
            id = id,
            participantsJson = gson.toJson(participants),
            title = title,
            lastReadAtJson = gson.toJson(lastReadAt),
            lastDeliveredAtJson = gson.toJson(lastDeliveredAt),
            lastMessageAtMillis = lastMessageAtMillis,
            archived = archived,
            createdAt = createdAt,
            syncedToFirestore = syncedToFirestore
        )
    }

    private fun MessageEntity.toDomain(): Message {
        val attachmentsListType = object : TypeToken<List<String>>() {}.type
        val attachments: List<String> = gson.fromJson(attachmentsJson, attachmentsListType)

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            content = content,
            timestamp = timestamp,
            messageType = MessageType.valueOf(messageType),
            attachments = attachments,
            isRead = isRead,
            replyToMessageId = replyToMessageId,
            syncedToFirestore = syncedToFirestore,
            status = try {
                MessageSendStatus.valueOf(status ?: "SENT")
            } catch (e: IllegalArgumentException) {
                MessageSendStatus.SENT // Default to SENT for old messages or invalid values
            }
        )
    }

    private fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            content = content,
            timestamp = timestamp,
            messageType = messageType.name,
            attachmentsJson = gson.toJson(attachments),
            isRead = isRead,
            replyToMessageId = replyToMessageId,
            syncedToFirestore = syncedToFirestore,
            status = status.name // Will never be null as Message always has a status
        )
    }

    private companion object {
        const val TAG = "MessageRepo"
    }
}
