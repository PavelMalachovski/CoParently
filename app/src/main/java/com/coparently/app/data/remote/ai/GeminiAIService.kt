package com.coparently.app.data.remote.ai

import android.util.Log
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.ai.*
import com.coparently.app.domain.usecase.ai.PromptSafety
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AIService using Google Gemini API.
 *
 * **Nothing here logs a model response.** Every method used to `Log.d` the raw `responseText`,
 * and the prompts these responses answer are built from the family's calendar, their chat
 * messages, their expenses and a child's details — so the whole point of the feature was being
 * written to logcat, where a bug report or a connected `adb` picks it up. `Log.e` on the failure
 * paths is kept: it carries the exception, not the content.
 */
@Singleton
class GeminiAIService @Inject constructor(
    @GeminiApiKey private val apiKey: String,
    private val gson: Gson
) : AIService {

    companion object {
        private const val TAG = "GeminiAIService"
        private const val MODEL_NAME = "gemini-1.5-flash"
    }

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.7f
                topK = 40
                topP = 0.95f
                maxOutputTokens = 2048
            }
        )
    }

    override suspend fun generateTimeSlotSuggestions(prompt: String): List<TimeSlotSuggestion> {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: return emptyList()

            parseTimeSlotSuggestions(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating time slot suggestions", e)
            emptyList()
        }
    }

    override suspend fun analyzeCalendarPatterns(calendars: List<CalendarData>): CalendarInsights {
        return try {
            val prompt = buildCalendarAnalysisPrompt(calendars)
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: return CalendarInsights(
                busyPeriods = emptyList(),
                freeTimePatterns = emptyList(),
                predictedConflicts = emptyList(),
                schedulingEfficiency = 0.0
            )

            parseCalendarInsights(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing calendar patterns", e)
            CalendarInsights(
                busyPeriods = emptyList(),
                freeTimePatterns = emptyList(),
                predictedConflicts = emptyList(),
                schedulingEfficiency = 0.0
            )
        }
    }

    override suspend fun suggestEventImprovements(
        event: Event,
        context: EventContext
    ): List<EventSuggestion> {
        return try {
            val prompt = buildEventImprovementPrompt(event, context)
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: return emptyList()

            parseEventSuggestions(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error suggesting event improvements", e)
            emptyList()
        }
    }

    override suspend fun parseNaturalLanguage(prompt: String): AIParseResponse {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw Exception("Empty response from AI")

            val parsedEvent = parseEventFromResponse(responseText)
            AIParseResponse(
                parsedEvent = parsedEvent,
                confidence = extractConfidence(responseText),
                rawResponse = responseText
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing natural language", e)
            throw e
        }
    }

    override suspend fun generateEventSuggestions(prompt: String): List<EventSuggestion> {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: return emptyList()

            parseEventSuggestions(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating event suggestions", e)
            emptyList()
        }
    }

    // Day 3: Communication Intelligence implementations

    override suspend fun analyzeSentiment(text: String): SentimentResult {
        return try {
            val prompt = """
                Analyze the sentiment of this message.

                ${PromptSafety.DATA_ONLY_PREAMBLE}

                ${PromptSafety.fence("MESSAGE", text)}

                Return in JSON format:
                {
                    "score": number between -1 (negative) and 1 (positive),
                    "magnitude": number representing emotional strength,
                    "label": one of "VERY_POSITIVE", "POSITIVE", "NEUTRAL", "NEGATIVE", "VERY_NEGATIVE"
                }
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw Exception("Empty response")

            parseSentimentResult(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing sentiment", e)
            SentimentResult(score = 0.0, magnitude = 0.0, label = SentimentLabel.NEUTRAL)
        }
    }

    override suspend fun analyzeCommunication(prompt: String): ToneAnalysisResponse {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw Exception("Empty response")

            parseToneAnalysisResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing communication", e)
            ToneAnalysisResponse(
                tone = "neutral",
                intensity = 5,
                potentialIssues = emptyList(),
                improvements = emptyList(),
                effectiveness = 0.5
            )
        }
    }

    override suspend fun generateMessageRewrites(prompt: String): List<String> {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: return emptyList()

            parseMessageRewrites(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating message rewrites", e)
            emptyList()
        }
    }

    override suspend fun generateConversationSummary(prompt: String): ConversationSummaryResponse {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw Exception("Empty response")

            parseConversationSummary(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating conversation summary", e)
            ConversationSummaryResponse(
                summary = "Unable to generate summary",
                keyPoints = emptyList(),
                actionItems = emptyList(),
                agreements = emptyList(),
                conflicts = emptyList(),
                overallSentiment = "neutral"
            )
        }
    }

    // Day 4: Family Insights & Analytics implementations

    override suspend fun generateFamilyInsights(prompt: String): FamilyInsightsResponse {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw Exception("Empty response")

            parseFamilyInsights(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating family insights", e)
            FamilyInsightsResponse(
                recommendations = emptyList(),
                concerns = emptyList(),
                positiveTrends = emptyList()
            )
        }
    }

    override suspend fun predictExpenseTrends(prompt: String): ExpenseTrendPrediction {
        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw Exception("Empty response")

            parseExpenseTrendPrediction(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error predicting expense trends", e)
            ExpenseTrendPrediction(
                monthlyPredictions = emptyList(),
                seasonalVariations = emptyMap(),
                expectedCostIncrease = 0.0,
                budgetRecommendations = listOf("Unable to generate predictions")
            )
        }
    }

    // Helper methods for parsing new responses

    private fun parseSentimentResult(responseText: String): SentimentResult {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject
                SentimentResult(
                    score = obj.get("score").asDouble,
                    magnitude = obj.get("magnitude").asDouble,
                    label = SentimentLabel.valueOf(obj.get("label").asString)
                )
            } else {
                SentimentResult(score = 0.0, magnitude = 0.0, label = SentimentLabel.NEUTRAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sentiment result", e)
            SentimentResult(score = 0.0, magnitude = 0.0, label = SentimentLabel.NEUTRAL)
        }
    }

    private fun parseToneAnalysisResponse(responseText: String): ToneAnalysisResponse {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject
                ToneAnalysisResponse(
                    tone = obj.get("tone").asString,
                    intensity = obj.get("intensity").asInt,
                    potentialIssues = obj.get("potentialIssues").asJsonArray.map { it.asString },
                    improvements = obj.get("improvements").asJsonArray.map { it.asString },
                    effectiveness = obj.get("effectiveness").asDouble
                )
            } else {
                ToneAnalysisResponse("neutral", 5, emptyList(), emptyList(), 0.5)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tone analysis", e)
            ToneAnalysisResponse("neutral", 5, emptyList(), emptyList(), 0.5)
        }
    }

    private fun parseMessageRewrites(responseText: String): List<String> {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val jsonArray = JsonParser.parseString(jsonMatch).asJsonArray
                jsonArray.map { it.asString }
            } else {
                // Try to parse plain text alternatives
                responseText.split("\n")
                    .filter { it.isNotBlank() && it.length > 10 }
                    .take(3)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message rewrites", e)
            emptyList()
        }
    }

    private fun parseConversationSummary(responseText: String): ConversationSummaryResponse {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject
                ConversationSummaryResponse(
                    summary = obj.get("summary").asString,
                    keyPoints = obj.get("keyPoints").asJsonArray.map { it.asString },
                    actionItems = obj.get("actionItems").asJsonArray.map { it.asString },
                    agreements = obj.get("agreements").asJsonArray.map { it.asString },
                    conflicts = obj.get("conflicts").asJsonArray.map { it.asString },
                    overallSentiment = obj.get("overallSentiment").asString
                )
            } else {
                ConversationSummaryResponse(
                    summary = responseText.take(200),
                    keyPoints = emptyList(),
                    actionItems = emptyList(),
                    agreements = emptyList(),
                    conflicts = emptyList(),
                    overallSentiment = "neutral"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing conversation summary", e)
            ConversationSummaryResponse(
                summary = "Error parsing summary",
                keyPoints = emptyList(),
                actionItems = emptyList(),
                agreements = emptyList(),
                conflicts = emptyList(),
                overallSentiment = "neutral"
            )
        }
    }

    private fun parseFamilyInsights(responseText: String): FamilyInsightsResponse {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject
                FamilyInsightsResponse(
                    recommendations = obj.get("recommendations").asJsonArray.map { it.asString },
                    concerns = obj.get("concerns").asJsonArray.map { it.asString },
                    positiveTrends = obj.get("positiveTrends").asJsonArray.map { it.asString }
                )
            } else {
                FamilyInsightsResponse(emptyList(), emptyList(), emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing family insights", e)
            FamilyInsightsResponse(emptyList(), emptyList(), emptyList())
        }
    }

    private fun parseExpenseTrendPrediction(responseText: String): ExpenseTrendPrediction {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject

                val monthlyPredictions = obj.get("monthlyPredictions").asJsonArray.map { element ->
                    val predObj = element.asJsonObject
                    MonthlyExpensePrediction(
                        month = predObj.get("month").asString,
                        predictedAmount = predObj.get("predictedAmount").asDouble,
                        confidence = predObj.get("confidence").asDouble,
                        breakdown = predObj.get("breakdown").asJsonObject.entrySet()
                            .associate { it.key to it.value.asDouble }
                    )
                }

                val seasonalVariations = obj.get("seasonalVariations").asJsonObject.entrySet()
                    .associate { it.key to it.value.asDouble }

                ExpenseTrendPrediction(
                    monthlyPredictions = monthlyPredictions,
                    seasonalVariations = seasonalVariations,
                    expectedCostIncrease = obj.get("expectedCostIncrease").asDouble,
                    budgetRecommendations = obj.get("budgetRecommendations").asJsonArray.map { it.asString }
                )
            } else {
                ExpenseTrendPrediction(
                    monthlyPredictions = emptyList(),
                    seasonalVariations = emptyMap(),
                    expectedCostIncrease = 0.0,
                    budgetRecommendations = emptyList()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing expense trend prediction", e)
            ExpenseTrendPrediction(
                monthlyPredictions = emptyList(),
                seasonalVariations = emptyMap(),
                expectedCostIncrease = 0.0,
                budgetRecommendations = emptyList()
            )
        }
    }

    // Helper methods for parsing responses


    private fun parseTimeSlotSuggestions(responseText: String): List<TimeSlotSuggestion> {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val jsonArray = JsonParser.parseString(jsonMatch).asJsonArray
                jsonArray.map { element ->
                    val obj = element.asJsonObject
                    TimeSlotSuggestion(
                        startTime = LocalDateTime.parse(
                            obj.get("startTime").asString,
                            DateTimeFormatter.ISO_DATE_TIME
                        ),
                        endTime = LocalDateTime.parse(
                            obj.get("endTime").asString,
                            DateTimeFormatter.ISO_DATE_TIME
                        ),
                        score = obj.get("score").asDouble,
                        reasoning = obj.get("reasoning").asString,
                        alternativeDates = emptyList()
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing time slot suggestions", e)
            emptyList()
        }
    }

    private fun parseCalendarInsights(responseText: String): CalendarInsights {
        return try {
            // For now, return basic insights - can be enhanced with JSON parsing
            CalendarInsights(
                busyPeriods = emptyList(),
                freeTimePatterns = emptyList(),
                predictedConflicts = emptyList(),
                schedulingEfficiency = 0.75
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing calendar insights", e)
            CalendarInsights(
                busyPeriods = emptyList(),
                freeTimePatterns = emptyList(),
                predictedConflicts = emptyList(),
                schedulingEfficiency = 0.0
            )
        }
    }

    private fun parseEventSuggestions(responseText: String): List<EventSuggestion> {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val jsonArray = JsonParser.parseString(jsonMatch).asJsonArray
                jsonArray.map { element ->
                    val obj = element.asJsonObject
                    EventSuggestion(
                        title = obj.get("title").asString,
                        description = obj.get("description").asString,
                        suggestedDateTime = LocalDateTime.parse(
                            obj.get("suggestedDateTime").asString,
                            DateTimeFormatter.ISO_DATE_TIME
                        ),
                        eventType = obj.get("eventType").asString,
                        confidence = obj.get("confidence").asDouble,
                        reasoning = obj.get("reasoning").asString,
                        category = SuggestionCategory.valueOf(
                            obj.get("category").asString.uppercase()
                        )
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing event suggestions", e)
            emptyList()
        }
    }

    private fun parseEventFromResponse(responseText: String): ParsedEvent {
        val jsonMatch = extractJsonFromResponse(responseText)
        return if (jsonMatch != null) {
            try {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject
                ParsedEvent(
                    title = obj.get("title")?.asString ?: "",
                    dateTime = obj.get("dateTime")?.asString?.let {
                        LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME)
                    },
                    duration = obj.get("duration")?.asInt?.let {
                        java.time.Duration.ofMinutes(it.toLong())
                    },
                    location = obj.get("location")?.asString,
                    eventType = obj.get("eventType")?.asString,
                    parentOwner = obj.get("parentOwner")?.asString,
                    isRecurring = obj.get("isRecurring")?.asBoolean ?: false,
                    recurrencePattern = obj.get("recurrencePattern")?.asString,
                    notes = obj.get("notes")?.asString
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing event JSON", e)
                // Fallback: parse from plain text
                parseEventFromPlainText(responseText)
            }
        } else {
            parseEventFromPlainText(responseText)
        }
    }

    private fun parseEventFromPlainText(text: String): ParsedEvent {
        // Simple fallback parsing
        return ParsedEvent(
            title = text.take(100),
            dateTime = LocalDateTime.now(),
            duration = java.time.Duration.ofHours(1),
            eventType = "general"
        )
    }

    private fun extractConfidence(responseText: String): Double {
        return try {
            val jsonMatch = extractJsonFromResponse(responseText)
            if (jsonMatch != null) {
                val obj = JsonParser.parseString(jsonMatch).asJsonObject
                obj.get("confidence")?.asDouble ?: 0.8
            } else {
                0.8
            }
        } catch (e: Exception) {
            0.8
        }
    }

    private fun extractJsonFromResponse(text: String): String? {
        val jsonPattern = Regex("\\{.*}", RegexOption.DOT_MATCHES_ALL)
        val arrayPattern = Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL)

        return arrayPattern.find(text)?.value ?: jsonPattern.find(text)?.value
    }

    private fun buildCalendarAnalysisPrompt(calendars: List<CalendarData>): String {
        return """
            Analyze these parent calendars and provide insights.

            ${PromptSafety.DATA_ONLY_PREAMBLE}

            ${PromptSafety.fence(
                "CALENDARS",
                calendars.joinToString("\n\n") { calendar ->
                    "Parent: ${calendar.parentName}\n" +
                        "Events:\n" +
                        calendar.events.joinToString("\n") {
                            "- ${it.title} on ${it.startDateTime} (${it.eventType})"
                        }
                }
            )}

            Provide analysis of:
            1. Busy periods
            2. Free time patterns
            3. Potential conflicts
            4. Scheduling efficiency

            Return as plain text.
        """.trimIndent()
    }

    private fun buildEventImprovementPrompt(event: Event, context: EventContext): String {
        return """
            Suggest improvements for this event.

            ${PromptSafety.DATA_ONLY_PREAMBLE}

            ${PromptSafety.fence(
                "EVENT",
                "Title: ${event.title}\n" +
                    "Type: ${event.eventType}\n" +
                    "Time: ${event.startDateTime}\n" +
                    "Description: ${event.description}"
            )}

            Recent events context:
            ${PromptSafety.fence(
                "RECENT EVENTS",
                context.recentEvents.take(5).joinToString("\n") { "- ${it.title}" }
            )}

            Suggest 2-3 improvements to make this event more effective.
            Consider timing, preparation, and coordination.

            Return as JSON array with: title, description, suggestedDateTime, eventType, confidence, reasoning, category
        """.trimIndent()
    }
}
