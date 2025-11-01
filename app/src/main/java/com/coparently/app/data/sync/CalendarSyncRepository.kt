package com.coparently.app.data.sync

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.google.CredentialProvider
import com.coparently.app.data.remote.google.GoogleCalendarApi
import com.coparently.app.domain.model.Event
import com.google.api.client.auth.oauth2.Credential
import com.google.api.services.calendar.model.Event as GoogleEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for synchronizing events between local database and Google Calendar.
 */
@Singleton
class CalendarSyncRepository @Inject constructor(
    private val eventDao: EventDao,
    private val googleCalendarApi: GoogleCalendarApi,
    private val credentialProvider: CredentialProvider
) {
    /**
     * Syncs events from Google Calendar to local database (pull).
     */
    suspend fun syncFromGoogle(
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null
    ): Flow<SyncResult> = flow {
        try {
            emit(SyncResult.Progress("Starting sync from Google Calendar..."))

            val credential = credentialProvider.getCredential()
                ?: throw IllegalStateException("Not authenticated")

            val googleEvents = googleCalendarApi.listEvents(
                credential = credential,
                timeMin = startDate,
                timeMax = endDate
            )

            emit(SyncResult.Progress("Found ${googleEvents.size} events in Google Calendar"))

            val eventsToInsert = mutableListOf<EventEntity>()

            googleEvents.forEach { googleEvent ->
                val eventEntity = googleEvent.toEventEntity()
                eventsToInsert.add(eventEntity)
            }

            eventDao.insertEvents(eventsToInsert)

            emit(SyncResult.Success("Synced ${eventsToInsert.size} events from Google Calendar"))
        } catch (e: Exception) {
            emit(SyncResult.Error(e.message ?: "Unknown error during sync"))
        }
    }

    /**
     * Syncs events from local database to Google Calendar (push).
     */
    suspend fun syncToGoogle(event: Event): Flow<SyncResult> = flow {
        try {
            emit(SyncResult.Progress("Syncing event to Google Calendar..."))

            val credential = credentialProvider.getCredential()
                ?: throw IllegalStateException("Not authenticated")

            val googleEvent = googleCalendarApi.createEvent(
                credential = credential,
                title = event.title,
                description = event.description,
                startDateTime = event.startDateTime,
                endDateTime = event.endDateTime
            )

            // Update local event with Google Calendar event ID
            val updatedEvent = event.copy(
                // Store googleEventId in a separate field if needed
            )

            emit(SyncResult.Success("Event synced to Google Calendar"))
        } catch (e: Exception) {
            emit(SyncResult.Error(e.message ?: "Unknown error during sync"))
        }
    }

    /**
     * Converts Google Calendar Event to EventEntity.
     */
    private fun GoogleEvent.toEventEntity(): EventEntity {
        val startDateTime = start?.dateTime?.value?.let {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it),
                ZoneId.systemDefault()
            )
        } ?: LocalDateTime.now()

        val endDateTime = end?.dateTime?.value?.let {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it),
                ZoneId.systemDefault()
            )
        }

        return EventEntity(
            id = id ?: java.util.UUID.randomUUID().toString(),
            title = summary ?: "Untitled Event",
            description = description,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            eventType = "google",
            parentOwner = "mom", // TODO: Determine from event data
            isRecurring = recurrence != null,
            recurrencePattern = recurrence?.firstOrNull()?.toString(),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }
}


/**
 * Result of synchronization operation.
 */
sealed class SyncResult {
    data class Progress(val message: String) : SyncResult()
    data class Success(val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

