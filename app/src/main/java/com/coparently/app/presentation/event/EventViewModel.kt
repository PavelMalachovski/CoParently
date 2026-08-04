package com.coparently.app.presentation.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.repository.EventImageStorage
import com.coparently.app.domain.repository.EventRepository
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for managing events.
 * Handles UI state and business logic for event operations using Use Cases.
 */
/**
 * Data class for event draft.
 * Issue 1.3: Draft saving functionality.
 */
data class EventDraft(
    val title: String,
    val description: String,
    val parentOwner: String,
    val eventType: String,
    val startDate: String, // ISO format
    val startTime: String, // ISO format
    val endTime: String // ISO format
)

/**
 * What the events list is currently showing.
 *
 * Modelling this as state rather than as a call is what guarantees a single live collection:
 * `flatMapLatest` cancels the previous one. Before August 2026 each load launched its own
 * coroutine and cancelled nothing, so the Calendar tab accumulated one permanent collector per
 * month swipe for the life of the process.
 */
internal sealed interface EventQuery {
    data object All : EventQuery
    data class Range(val start: LocalDateTime, val end: LocalDateTime) : EventQuery
}

@HiltViewModel
class EventViewModel @Inject constructor(
    private val eventUseCases: com.coparently.app.domain.usecase.EventUseCases,
    private val errorHandler: com.coparently.app.domain.error.ErrorHandler,
    private val encryptedPreferences: EncryptedPreferences,
    private val gson: Gson,
    private val userRepository: com.coparently.app.domain.repository.UserRepository,
    private val eventImageStorage: EventImageStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow<EventUiState>(EventUiState.Loading)
    val uiState: StateFlow<EventUiState> = _uiState.asStateFlow()

    // Re-requesting the range already loaded costs nothing: MutableStateFlow conflates equal
    // values, and EventQuery.Range is a data class, so setting it again emits nothing at all.
    private val query = MutableStateFlow<EventQuery>(EventQuery.All)

    // A separate tick, not a field on EventQuery.Range: folding it into Range would make every
    // instance of the same range compare unequal, and the conflation above (the whole reason
    // ordinary paging is free) would stop working. combine() re-emits whenever either source
    // does, so bumping this alone is what forces flatMapLatest to re-subscribe to the current
    // query without changing what that query is.
    private val refreshTicks = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<Event>> = combine(query, refreshTicks) { current, _ -> current }
        .flatMapLatest { current ->
            // This when() sits upstream of the .catch below, inside the flatMapLatest lambda.
            // It happens not to throw today (GetEventsUseCase only delegates, and
            // EventRepositoryImpl.getEventsByDateRange returns a combine() whose body runs
            // inside the flow, not here) but nothing enforces that: if a future repository
            // change makes either branch throw synchronously, that throw escapes onto the
            // outer chain, uncaught, and permanently kills the pipeline - the exact failure
            // this whole design exists to prevent. Keep the source selection lazy (a flow, not
            // a value) so any failure surfaces inside the flow below, where .catch can see it.
            val source = when (current) {
                is EventQuery.All -> eventUseCases.getEvents()
                is EventQuery.Range -> eventUseCases.getEvents.getByDateRange(current.start, current.end)
            }
            source
                .onStart { _uiState.value = EventUiState.Loading }
                .onEach { _uiState.value = EventUiState.Success(it) }
                // Caught per query, not on the outer chain: a failure of one range must not
                // end the flatMapLatest and leave every later query silently unserved.
                .catch { e -> _uiState.value = EventUiState.Error(errorHandler.handleError(e).userMessage) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Shows every event. `query`'s initial value, so this is what the view model shows before
     * anything else is requested - no screen currently calls it.
     */
    fun loadEvents() {
        query.value = EventQuery.All
    }

    /**
     * Shows the events between [start] and [end]. Re-requesting the range currently loaded is a
     * no-op, which is why the calendar can call this freely as the user pages.
     */
    fun loadEventsForDateRange(start: LocalDateTime, end: LocalDateTime) {
        query.value = EventQuery.Range(start, end)
    }

    /**
     * Re-collects the current query from scratch. `catch` completes the inner flow on error, so
     * a failed query stays failed - the equal-value conflation on [query] means re-requesting
     * the same range is a no-op and cannot restart it. This is the only way back: pull-to-refresh
     * calls it instead of re-requesting the range it already has.
     */
    fun refresh() {
        refreshTicks.value += 1
    }

    /**
     * Uploads a picked image as the photo for [eventId] and returns its download URL.
     * Callers should upload before saving the event so the URL can be stored on it.
     */
    suspend fun uploadEventImage(eventId: String, localUri: String): String =
        eventImageStorage.uploadEventImage(eventId, localUri)

    /**
     * Best-effort deletion of an event's remote photo; failures are swallowed so an
     * orphaned image never blocks the event save/delete.
     */
    suspend fun deleteEventImage(eventId: String) {
        runCatching { eventImageStorage.deleteEventImage(eventId) }
    }

    /**
     * Creates a new event.
     */
    fun createEvent(event: Event) {
        viewModelScope.launch {
            _uiState.value = EventUiState.Loading
            val result = eventUseCases.createEvent(event)
            result.onSuccess {
                _uiState.value = EventUiState.OperationSuccess("Event created successfully")
                kotlinx.coroutines.delay(2000)
                _uiState.value = EventUiState.Success(events.value)
            }.onFailure { error ->
                val appError = errorHandler.handleError(error)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Updates an existing event.
     */
    fun updateEvent(event: Event) {
        viewModelScope.launch {
            val result = eventUseCases.updateEvent(event)
            result.onSuccess {
                _uiState.value = EventUiState.OperationSuccess("Event updated successfully")
                kotlinx.coroutines.delay(2000)
                _uiState.value = EventUiState.Success(events.value)
            }.onFailure { error ->
                val appError = errorHandler.handleError(error)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Deletes an event.
     */
    fun deleteEvent(event: Event) {
        viewModelScope.launch {
            val result = eventUseCases.deleteEvent(event)
            result.onSuccess {
                if (event.imageUrl != null) deleteEventImage(event.id)
                _uiState.value = EventUiState.OperationSuccess("Event deleted successfully")
                kotlinx.coroutines.delay(2000)
                _uiState.value = EventUiState.Success(events.value)
            }.onFailure { error ->
                val appError = errorHandler.handleError(error)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Deletes an event by ID.
     */
    fun deleteEventById(id: String) {
        viewModelScope.launch {
            val result = eventUseCases.deleteEvent.deleteById(id)
            result.onSuccess {
                _uiState.value = EventUiState.OperationSuccess("Event deleted successfully")
                kotlinx.coroutines.delay(2000)
                _uiState.value = EventUiState.Success(events.value)
            }.onFailure { error ->
                val appError = errorHandler.handleError(error)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Data class to store previous event position for undo functionality.
     */
    data class PreviousEventPosition(
        val eventId: String,
        val previousDate: LocalDate,
        val previousHour: Int?
    )

    private var lastMoveUndoInfo: PreviousEventPosition? = null

    /**
     * Moves event to a new date/time (drag & drop support).
     * @param targetStartMinuteOfDay New start time as minute-of-day (0..1439), already
     *   snapped to 15 minutes by the caller; null keeps the original time of day
     *   (used by the month view, which only changes the date).
     * Stores previous position for undo functionality.
     */
    fun moveEvent(eventId: String, targetDate: LocalDate, targetStartMinuteOfDay: Int? = null) {
        viewModelScope.launch {
            try {
                val event = eventUseCases.getEvents.getById(eventId) ?: return@launch

                // Store previous position for undo
                val previousDate = event.startDateTime.toLocalDate()
                val previousHour = event.startDateTime.hour
                lastMoveUndoInfo = PreviousEventPosition(eventId, previousDate, previousHour)

                val duration = Duration.between(event.startDateTime, event.endDateTime)
                val newTime = targetStartMinuteOfDay
                    ?.coerceIn(0, 24 * 60 - 1)
                    ?.let { LocalTime.of(it / 60, it % 60) }
                    ?: event.startDateTime.toLocalTime()
                val newStart = targetDate.atTime(newTime)
                val newEnd = newStart.plus(duration)
                val updatedEvent = event.copy(
                    startDateTime = newStart,
                    endDateTime = newEnd
                )
                val result = eventUseCases.updateEvent(updatedEvent)
                result.onSuccess {
                    _uiState.value = EventUiState.OperationSuccess("Event rescheduled")
                    kotlinx.coroutines.delay(1500)
                    _uiState.value = EventUiState.Success(events.value)
                }.onFailure { error ->
                    val appError = errorHandler.handleError(error)
                    _uiState.value = EventUiState.Error(appError.userMessage)
                    lastMoveUndoInfo = null // Clear undo info on error
                }
            } catch (e: Exception) {
                val appError = errorHandler.handleError(e)
                _uiState.value = EventUiState.Error(appError.userMessage)
                lastMoveUndoInfo = null // Clear undo info on error
            }
        }
    }

    /**
     * Undoes the last event move operation.
     * Restores event to its previous position.
     */
    fun undoLastMove() {
        val undoInfo = lastMoveUndoInfo ?: return
        viewModelScope.launch {
            try {
                val event = eventUseCases.getEvents.getById(undoInfo.eventId) ?: return@launch
                val duration = Duration.between(event.startDateTime, event.endDateTime)
                val originalTime = event.startDateTime.toLocalTime()
                val previousTime = undoInfo.previousHour?.let { originalTime.withHour(it) } ?: originalTime
                val previousStart = undoInfo.previousDate.atTime(previousTime)
                val previousEnd = previousStart.plus(duration)
                val restoredEvent = event.copy(
                    startDateTime = previousStart,
                    endDateTime = previousEnd
                )
                val result = eventUseCases.updateEvent(restoredEvent)
                result.onSuccess {
                    lastMoveUndoInfo = null // Clear undo info after successful undo
                    _uiState.value = EventUiState.OperationSuccess("Move undone")
                    kotlinx.coroutines.delay(1500)
                    _uiState.value = EventUiState.Success(events.value)
                }.onFailure { error ->
                    val appError = errorHandler.handleError(error)
                    _uiState.value = EventUiState.Error(appError.userMessage)
                }
            } catch (e: Exception) {
                val appError = errorHandler.handleError(e)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Checks if there's an undo action available.
     */
    fun hasUndoAction(): Boolean = lastMoveUndoInfo != null

    /**
     * Resizes event by changing start or end time.
     * @param eventId The ID of the event to resize
     * @param newStartTime New start time (null if not changing)
     * @param newEndTime New end time (null if not changing)
     */
    fun resizeEvent(eventId: String, newStartTime: LocalDateTime? = null, newEndTime: LocalDateTime? = null) {
        viewModelScope.launch {
            try {
                val event = eventUseCases.getEvents.getById(eventId) ?: return@launch

                val updatedStart = newStartTime ?: event.startDateTime
                val updatedEnd = newEndTime ?: (event.endDateTime ?: event.startDateTime.plusHours(1))

                // Validate that end is after start
                if (updatedEnd.isBefore(updatedStart) || updatedEnd.isEqual(updatedStart)) {
                    _uiState.value = EventUiState.Error("End time must be after start time")
                    return@launch
                }

                val updatedEvent = event.copy(
                    startDateTime = updatedStart,
                    endDateTime = updatedEnd
                )
                val result = eventUseCases.updateEvent(updatedEvent)
                result.onSuccess {
                    _uiState.value = EventUiState.OperationSuccess("Event resized")
                    kotlinx.coroutines.delay(1500)
                    _uiState.value = EventUiState.Success(events.value)
                }.onFailure { error ->
                    val appError = errorHandler.handleError(error)
                    _uiState.value = EventUiState.Error(appError.userMessage)
                }
            } catch (e: Exception) {
                val appError = errorHandler.handleError(e)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Gets an event by ID.
     */
    suspend fun getEventById(id: String): Event? {
        return eventUseCases.getEvents.getById(id)
    }

    /**
     * Confirms the pickup for an event on behalf of the current user.
     * The other parent sees the confirmation through the regular event sync.
     */
    fun confirmPickup(eventId: String) {
        viewModelScope.launch {
            try {
                val event = eventUseCases.getEvents.getById(eventId) ?: return@launch
                val role = userRepository.getCurrentUser()?.role ?: event.parentOwner
                val updated = event.copy(
                    pickupConfirmedBy = role,
                    pickupConfirmedAt = LocalDateTime.now()
                )
                val result = eventUseCases.updateEvent(updated)
                result.onSuccess {
                    _uiState.value = EventUiState.OperationSuccess("Pickup confirmed")
                    kotlinx.coroutines.delay(1500)
                    _uiState.value = EventUiState.Success(events.value)
                }.onFailure { error ->
                    val appError = errorHandler.handleError(error)
                    _uiState.value = EventUiState.Error(appError.userMessage)
                }
            } catch (e: Exception) {
                val appError = errorHandler.handleError(e)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Removes an existing pickup confirmation from an event.
     */
    fun undoPickupConfirmation(eventId: String) {
        viewModelScope.launch {
            try {
                val event = eventUseCases.getEvents.getById(eventId) ?: return@launch
                val updated = event.copy(
                    pickupConfirmedBy = null,
                    pickupConfirmedAt = null
                )
                eventUseCases.updateEvent(updated)
            } catch (e: Exception) {
                val appError = errorHandler.handleError(e)
                _uiState.value = EventUiState.Error(appError.userMessage)
            }
        }
    }

    /**
     * Saves event draft to local storage.
     * Issue 1.3: Draft saving functionality.
     */
    fun saveEventDraft(
        title: String,
        description: String,
        parentOwner: String,
        eventType: String,
        startDate: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val draft = EventDraft(
                    title = title,
                    description = description,
                    parentOwner = parentOwner,
                    eventType = eventType,
                    startDate = startDate.toString(),
                    startTime = startTime.toString(),
                    endTime = endTime.toString()
                )
                val draftJson = gson.toJson(draft)
                // EncryptedSharedPreferences does crypto on the calling thread; keep it off Main.
                encryptedPreferences.putEventDraft(draftJson)
            } catch (e: Exception) {
                // Silently fail - draft saving is not critical
            }
        }
    }

    /**
     * Loads event draft from local storage.
     * Issue 1.3: Draft saving functionality.
     */
    fun loadEventDraft(): EventDraft? {
        return try {
            val draftJson = encryptedPreferences.getEventDraft() ?: return null
            gson.fromJson(draftJson, EventDraft::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears event draft from local storage.
     * Issue 1.3: Draft saving functionality.
     */
    fun clearEventDraft() {
        encryptedPreferences.clearEventDraft()
    }

    /**
     * User-defined event types created via the calendar filter sheet.
     */
    fun customEventTypes(): List<String> {
        return encryptedPreferences
            .getString(com.coparently.app.data.local.preferences.PreferenceKeys.CUSTOM_EVENT_TYPES)
            ?.split(com.coparently.app.data.local.preferences.PreferenceKeys.LIST_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }
}

/**
 * UI state for events.
 */
sealed class EventUiState {
    /**
     * Loading state - показываем Skeleton Loading
     */
    data object Loading : EventUiState()

    /**
     * Success state - данные загружены успешно
     */
    data class Success(val events: List<Event>) : EventUiState()

    /**
     * Operation Success state - операция (создание/обновление/удаление) выполнена успешно
     * Показываем Lottie Success анимацию
     */
    data class OperationSuccess(val message: String = "Operation completed successfully") : EventUiState()

    /**
     * Error state - произошла ошибка, показываем Lottie Error анимацию
     */
    data class Error(val message: String) : EventUiState()
}

