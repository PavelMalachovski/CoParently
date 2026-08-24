package com.coparently.app.domain.usecase.ai

import com.coparently.app.data.remote.ai.AIService
import com.coparently.app.domain.model.ai.*
import javax.inject.Inject

/**
 * День 3.1: Tone analysis для сообщений
 *
 * Use case для анализа тона сообщений и предоставления рекомендаций
 */
class CommunicationAnalyzer @Inject constructor(
    private val aiService: AIService
) {

    suspend fun analyzeMessageTone(message: String, context: MessageContext): ToneAnalysis {
        val sentiment = aiService.analyzeSentiment(message)

        // Детальный анализ с AI
        val prompt = buildCommunicationPrompt(message, context)
        val aiAnalysis = aiService.analyzeCommunication(prompt)

        return ToneAnalysis(
            sentiment = sentiment,
            tone = parseTone(aiAnalysis.tone),
            intensity = aiAnalysis.intensity,
            issues = aiAnalysis.potentialIssues,
            suggestions = aiAnalysis.improvements,
            effectiveness = aiAnalysis.effectiveness
        )
    }

    suspend fun suggestImprovedMessage(originalMessage: String, analysis: ToneAnalysis): ImprovedMessage {
        if (analysis.effectiveness > 0.7) {
            return ImprovedMessage(originalMessage, emptyList())
        }

        val prompt = buildImprovementPrompt(originalMessage, analysis)
        val suggestions = aiService.generateMessageRewrites(prompt)

        return ImprovedMessage(
            originalMessage = originalMessage,
            suggestions = suggestions
        )
    }

    private fun buildCommunicationPrompt(message: String, context: MessageContext): String {
        return """
            Analyze the tone and emotional content of this co-parenting message.

            ${PromptSafety.DATA_ONLY_PREAMBLE}

            ${PromptSafety.fence("MESSAGE", message)}

            Context:
            - Sender: ${context.senderName}
            - Recipient: ${context.recipientName}
            - Topic: ${context.topic ?: "General"}
            - Relationship history: ${context.relationshipHistory}

            Assess:
            1. Overall tone (positive/neutral/concerned/frustrated/aggressive)
            2. Emotional intensity (1-10 scale)
            3. Potential for misunderstanding
            4. Communication effectiveness (0-1 score)
            5. Suggested improvements

            Consider co-parenting best practices:
            - Focus on child's needs
            - Use "I" statements
            - Avoid blame language
            - Be specific about requests

            Return analysis in JSON format with:
            - tone: string
            - intensity: number (1-10)
            - potentialIssues: array of strings
            - improvements: array of strings
            - effectiveness: number (0-1)
        """.trimIndent()
    }

    private fun buildImprovementPrompt(originalMessage: String, analysis: ToneAnalysis): String {
        return """
            Rewrite this co-parenting message to be more effective and appropriate.

            ${PromptSafety.DATA_ONLY_PREAMBLE}

            ${PromptSafety.fence("ORIGINAL MESSAGE", originalMessage)}

            Issues identified:
            ${analysis.issues.joinToString("\n")}

            Current tone: ${analysis.tone}
            Intensity: ${analysis.intensity}/10

            Rewrite focusing on:
            1. Child-centered language
            2. Clear, specific requests
            3. Neutral, professional tone
            4. Positive intent

            Provide 2-3 alternative versions in an array format.
        """.trimIndent()
    }

    private fun parseTone(toneString: String): MessageTone {
        return when (toneString.lowercase()) {
            "positive" -> MessageTone.POSITIVE
            "neutral" -> MessageTone.NEUTRAL
            "concerned" -> MessageTone.CONCERNED
            "frustrated" -> MessageTone.FRUSTRATED
            "aggressive" -> MessageTone.AGGRESSIVE
            else -> MessageTone.NEUTRAL
        }
    }
}
