package com.coparently.app.presentation.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.ai.EventSuggestion
import com.coparently.app.domain.model.ai.SuggestionContext
import com.coparently.app.domain.model.ai.UserAction
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.usecase.ai.EventSuggestionEngineUseCase
import com.coparently.app.presentation.common.ParentsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for AI event suggestions
 * Day 2 - Feature 2.2: Smart event suggestions
 */
@HiltViewModel
class EventSuggestionsViewModel @Inject constructor(
    private val eventSuggestionEngineUseCase: EventSuggestionEngineUseCase,
    private val eventRepository: EventRepository,
    private val parentsSource: ParentsSource
) : ViewModel() {

    private val _suggestionsState = MutableStateFlow<SuggestionsUiState>(SuggestionsUiState.Loading)
    val suggestionsState: StateFlow<SuggestionsUiState> = _suggestionsState.asStateFlow()

    fun loadSuggestions(userId: String, context: SuggestionContext? = null) {
        viewModelScope.launch {
            _suggestionsState.value = SuggestionsUiState.Loading
            try {
                val suggestionContext = context ?: SuggestionContext(userId = userId)
                val suggestions = eventSuggestionEngineUseCase.generateEventSuggestions(
                    userId = userId,
                    context = suggestionContext
                )
                _suggestionsState.value = if (suggestions.isEmpty()) {
                    SuggestionsUiState.NoSuggestions
                } else {
                    SuggestionsUiState.Success(suggestions)
                }
            } catch (e: Exception) {
                _suggestionsState.value = SuggestionsUiState.Error(
                    e.message ?: "Failed to load suggestions"
                )
            }
        }
    }

    fun acceptSuggestion(suggestion: EventSuggestion) {
        viewModelScope.launch {
            try {
                // Attributed to whoever accepted it - the same "yours by default" rule
                // AddEditEventScreen applies. signedInSlot() is the cheap ParentsSource lookup
                // built for exactly this; null (not signed in, or a profile Room never named)
                // means there is nothing sensible to attribute the event to, so skip it rather
                // than guess a slot.
                val ownerSlot = parentsSource.signedInSlot() ?: return@launch
                // Convert suggestion to event and save
                val event = suggestion.toEvent(ownerSlot)
                eventRepository.insertEvent(event)

                // Learn from user action
                eventSuggestionEngineUseCase.learnFromUserAction(suggestion, UserAction.ACCEPTED)

                // Remove from suggestions
                removeSuggestion(suggestion)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun rejectSuggestion(suggestion: EventSuggestion) {
        viewModelScope.launch {
            eventSuggestionEngineUseCase.learnFromUserAction(suggestion, UserAction.REJECTED)
            removeSuggestion(suggestion)
        }
    }

    fun modifySuggestion(suggestion: EventSuggestion) {
        viewModelScope.launch {
            eventSuggestionEngineUseCase.learnFromUserAction(suggestion, UserAction.MODIFIED)
            // Navigation to event creation screen would happen in the UI layer
        }
    }

    private fun removeSuggestion(suggestion: EventSuggestion) {
        val currentState = _suggestionsState.value
        if (currentState is SuggestionsUiState.Success) {
            val updatedSuggestions = currentState.suggestions.filter { it.id != suggestion.id }
            _suggestionsState.value = if (updatedSuggestions.isEmpty()) {
                SuggestionsUiState.NoSuggestions
            } else {
                SuggestionsUiState.Success(updatedSuggestions)
            }
        }
    }
}

sealed class SuggestionsUiState {
    data object Loading : SuggestionsUiState()
    data object NoSuggestions : SuggestionsUiState()
    data class Success(val suggestions: List<EventSuggestion>) : SuggestionsUiState()
    data class Error(val message: String) : SuggestionsUiState()
}

// Extension to convert EventSuggestion to Event
private fun EventSuggestion.toEvent(ownerSlot: String): com.coparently.app.domain.model.Event {
    val now = java.time.LocalDateTime.now()
    return com.coparently.app.domain.model.Event(
        id = java.util.UUID.randomUUID().toString(),
        title = this.title,
        description = this.description,
        startDateTime = this.suggestedDateTime,
        endDateTime = this.suggestedDateTime.plusHours(1),
        eventType = this.eventType,
        parentOwner = ownerSlot,
        createdAt = now,
        updatedAt = now
    )
}
