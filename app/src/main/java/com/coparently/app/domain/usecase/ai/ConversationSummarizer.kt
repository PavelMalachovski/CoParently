package com.coparently.app.domain.usecase.ai

import com.coparently.app.data.remote.ai.AIService
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.ai.ActionItem
import com.coparently.app.domain.model.ai.ActionItemStatus
import com.coparently.app.domain.model.ai.ConversationSummary
import com.coparently.app.domain.model.ai.MessageTone
import com.coparently.app.domain.repository.MessageRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * День 3.2: Smart conversation summaries
 *
 * Use case для создания умных резюме разговоров
 */
class ConversationSummarizer @Inject constructor(
    private val aiService: AIService,
    private val messageRepository: MessageRepository
) {

    suspend fun summarizeConversation(
        conversationId: String,
        since: LocalDateTime? = null
    ): ConversationSummary {

        val messages = getMessagesSince(conversationId, since ?: LocalDateTime.now().minusDays(7))

        if (messages.isEmpty()) {
            return ConversationSummary.Empty
        }

        val prompt = buildSummaryPrompt(messages)
        val aiSummary = aiService.generateConversationSummary(prompt)

        return ConversationSummary.Content(
            summary = aiSummary.summary,
            keyPoints = aiSummary.keyPoints,
            actionItems = aiSummary.actionItems,
            agreements = aiSummary.agreements,
            conflicts = aiSummary.conflicts,
            sentiment = parseSentiment(aiSummary.overallSentiment),
            timeRange = messages.first().localSentAt() to messages.last().localSentAt()
        )
    }

    /**
     * The message's send instant as a local date-time, for the summary's human-facing range.
     *
     * `Message.sentAtMillis` is an instant; `ConversationSummary.timeRange` describes it to a
     * reader, so it is resolved in this device's zone — the same zone the chat renders in.
     */
    private fun Message.localSentAt(): LocalDateTime =
        Instant.ofEpochMilli(sentAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()

    suspend fun extractActionItems(conversationId: String): List<ActionItem> {
        val summary = summarizeConversation(conversationId)

        return when (summary) {
            is ConversationSummary.Content -> summary.actionItems.map { actionText ->
                ActionItem(
                    id = UUID.randomUUID().toString(),
                    description = actionText,
                    conversationId = conversationId,
                    status = ActionItemStatus.PENDING,
                    createdAt = LocalDateTime.now()
                )
            }
            else -> emptyList()
        }
    }

    private suspend fun getMessagesSince(conversationId: String, since: LocalDateTime): List<Message> {
        // Get messages from repository
        // This would need to be implemented in MessageRepository
        // For now, we'll return an empty list as placeholder
        // TODO: Add method to MessageRepository to get messages since a specific date
        return emptyList()
    }

    private fun buildSummaryPrompt(messages: List<Message>): String {
        val messageText = messages.joinToString("\n") { msg ->
            "${msg.senderName}: ${msg.content}"
        }

        return """
            Summarize this co-parenting conversation:

            Messages:
            $messageText

            Provide:
            1. Brief summary of discussion
            2. Key decisions/agreements made
            3. Action items (who needs to do what)
            4. Any conflicts or disagreements
            5. Overall tone/sentiment
            6. Important dates mentioned

            Focus on co-parenting topics like:
            - Child custody and visitation
            - School and education
            - Medical care and appointments
            - Expenses and financial matters
            - Communication preferences

            Return in JSON format with:
            - summary: string
            - keyPoints: array of strings
            - actionItems: array of strings
            - agreements: array of strings
            - conflicts: array of strings
            - overallSentiment: string (positive/neutral/concerned/frustrated/aggressive)
        """.trimIndent()
    }

    private fun parseSentiment(sentimentString: String): MessageTone {
        return when (sentimentString.lowercase()) {
            "positive" -> MessageTone.POSITIVE
            "neutral" -> MessageTone.NEUTRAL
            "concerned" -> MessageTone.CONCERNED
            "frustrated" -> MessageTone.FRUSTRATED
            "aggressive" -> MessageTone.AGGRESSIVE
            else -> MessageTone.NEUTRAL
        }
    }
}
