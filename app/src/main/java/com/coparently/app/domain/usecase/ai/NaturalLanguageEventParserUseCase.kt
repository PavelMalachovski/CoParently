package com.coparently.app.domain.usecase.ai

import android.util.Log
import com.coparently.app.data.remote.ai.AIService
import com.coparently.app.data.remote.ai.EventContext
import com.coparently.app.domain.model.ai.*
import com.coparently.app.domain.repository.EventRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/**
 * Use case for parsing natural language into events
 * Day 2 - Feature 2.1: Voice-to-event conversion
 */
class NaturalLanguageEventParserUseCase @Inject constructor(
    private val aiService: AIService,
    private val eventRepository: EventRepository,
    private val eventValidator: EventValidator
) {
    companion object {
        private const val TAG = "NaturalLanguageEventParser"
    }

    suspend fun parseEventFromText(
        text: String,
        context: ParsingContext = ParsingContext()
    ): ParsedEventResult {
        return try {
            val prompt = buildParsingPrompt(text, context)
            val aiResponse = aiService.parseNaturalLanguage(prompt)
            val parsedEvent = aiResponse.parsedEvent

            // Validate the parsed event
            val event = parsedEvent.toEvent()
            val validation = eventValidator.validate(event)

            ParsedEventResult.Success(
                event = parsedEvent,
                confidence = aiResponse.confidence,
                validationIssues = validation.errors
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse event", e)
            ParsedEventResult.Error("Failed to parse event: ${e.message}")
        }
    }

    private suspend fun buildParsingPrompt(
        text: String,
        context: ParsingContext
    ): String {
        // Get recent events for context
        val recentEvents = eventRepository.getAllEvents().first().take(10)

        return """
            Parse the following natural language text into a structured calendar event.

            ${PromptSafety.DATA_ONLY_PREAMBLE}

            ${PromptSafety.fence("TEXT TO PARSE", text)}

            Context:
            - Current date: ${context.currentDate}
            - User timezone: ${context.timezone}
            - Common locations: ${context.commonLocations.joinToString()}

            Recent event titles, for disambiguation only:
            ${PromptSafety.fence("RECENT EVENT TITLES", recentEvents.joinToString("\n") { it.title })}

            Extract:
            1. Event title
            2. Date and time (if not specified, infer from context - use ISO 8601 format)
            3. Duration in minutes (if not specified, use defaults: 60 for general, 30 for appointments)
            4. Location (if mentioned)
            5. Event type (school, medical, sports, work, general, etc.)
            6. Parent assignment (mom/dad/both)
            7. Recurrence pattern (if mentioned: daily, weekly, monthly)
            8. Additional notes

            Consider common patterns:
            - "Soccer practice tomorrow at 3pm" -> Sports event
            - "Doctor appointment next Tuesday" -> Medical event
            - "School pickup Friday" -> School event
            - "Dentist 2pm Wednesday" -> Medical event

            Return JSON: {"title": "...", "dateTime": "2024-01-01T10:00:00", "duration": 60, "location": "...", "eventType": "...", "parentOwner": "...", "isRecurring": false, "recurrencePattern": null, "notes": "...", "confidence": 0.95}
        """.trimIndent()
    }
}

/**
 * Event validator for validating parsed events
 */
class EventValidator @Inject constructor() {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList()
    )

    fun validate(event: com.coparently.app.domain.model.Event): ValidationResult {
        val errors = mutableListOf<String>()

        if (event.title.isBlank()) {
            errors.add("Event title cannot be empty")
        }

        if (event.endDateTime != null && event.endDateTime.isBefore(event.startDateTime)) {
            errors.add("End time cannot be before start time")
        }

        if (event.parentOwner !in listOf("mom", "dad", "both")) {
            errors.add("Invalid parent owner: must be 'mom', 'dad', or 'both'")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}
