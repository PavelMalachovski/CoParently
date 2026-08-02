package com.coparently.app.data.repository

import com.coparently.app.domain.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two chat-side steps a fresh pairing (or every launch while already paired) needs, run as
 * one unit.
 *
 * Bundling them keeps [com.coparently.app.data.repository.PairingRepositoryImpl]'s constructor
 * at the same parameter count it had before the legacy-conversation merge existed — it takes one
 * new collaborator instead of two — and it encodes the ordering dependency between them in one
 * place: [ConversationMigrator.mergeLegacyConversations] both reads and writes the canonical
 * conversation, so it must never run before [MessageRepository.ensureConversation] has created
 * it.
 */
@Singleton
class PostPairingConversationSetup @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationMigrator: ConversationMigrator
) {

    /**
     * Ensures the canonical conversation exists, then folds any legacy conversation for the
     * same pair into it.
     *
     * @param myUid This device's Firebase uid.
     * @param partnerUid The paired co-parent's Firebase uid.
     * @param partnerName The partner's display name, used to title a newly created conversation.
     */
    suspend fun run(myUid: String, partnerUid: String, partnerName: String) {
        messageRepository.ensureConversation(myUid, partnerUid, partnerName)
        conversationMigrator.mergeLegacyConversations(myUid, partnerUid)
    }
}
