package com.coparently.app.data.sync

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.google.CredentialProvider
import com.coparently.app.data.remote.google.CredentialProviderImpl
import com.coparently.app.data.remote.google.GoogleCalendarApi
import com.coparently.app.domain.model.Event
import com.google.api.client.auth.oauth2.Credential
import com.google.api.services.calendar.model.Event as GoogleEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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

            var credential = credentialProvider.getCredential()
                ?: throw IllegalStateException("Not authenticated. Please sign in to Google.")

            // Refresh token if needed (in case it expired)
            if (credentialProvider is CredentialProviderImpl) {
                credentialProvider.refreshAccessToken()
                // Get credential again with refreshed token
                credential = credentialProvider.getCredential()
                    ?: throw IllegalStateException("Failed to get credential after token refresh.")
            }

            emit(SyncResult.Progress("Fetching events from Google Calendar..."))

            // Execute API call on IO dispatcher to avoid NetworkOnMainThreadException
            val googleEvents = withContext(Dispatchers.IO) {
                googleCalendarApi.listEvents(
                    credential = credential,
                    timeMin = startDate,
                    timeMax = endDate
                )
            }

            emit(SyncResult.Progress("Found ${googleEvents.size} events in Google Calendar"))

            val eventsToInsert = mutableListOf<EventEntity>()

            googleEvents.forEach { googleEvent ->
                val eventEntity = googleEvent.toEventEntity()
                eventsToInsert.add(eventEntity)
            }

            if (eventsToInsert.isNotEmpty()) {
                eventDao.insertEvents(eventsToInsert)
            }

            emit(SyncResult.Success("Synced ${eventsToInsert.size} events from Google Calendar"))
        } catch (e: IllegalStateException) {
            android.util.Log.e("CalendarSync", "Authentication error: ${e.message}", e)
            emit(SyncResult.Error(e.message ?: "Authentication error. Please sign in again."))
        } catch (e: android.os.NetworkOnMainThreadException) {
            android.util.Log.e("CalendarSync", "NetworkOnMainThreadException: API call must be on background thread", e)
            emit(SyncResult.Error("Synchronization error: Network operation cannot run on main thread. Please try again."))
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            // Google API specific errors
            android.util.Log.e("CalendarSync", "Google API error: ${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                401 -> "Authentication failed. Please sign in again."
                403 -> "Access denied. Please check Calendar permission in Google settings."
                404 -> "Calendar not found. Please check your Google Calendar."
                429 -> "Too many requests. Please try again later."
                else -> "Google Calendar API error: ${e.statusCode} - ${e.message ?: "Unknown error"}"
            }
            emit(SyncResult.Error(errorMsg))
        } catch (e: java.io.IOException) {
            android.util.Log.e("CalendarSync", "Network error: ${e.message}", e)
            emit(SyncResult.Error("Network error: ${e.message ?: "Unable to connect to Google Calendar. Please check your internet connection."}"))
        } catch (e: com.google.api.client.http.HttpResponseException) {
            // HTTP response errors
            android.util.Log.e("CalendarSync", "HTTP error: ${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                401 -> "Authentication failed. Please sign in again."
                403 -> "Access denied. Please check Calendar permission."
                404 -> "Calendar not found."
                500, 503 -> "Google Calendar service unavailable. Please try again later."
                else -> "HTTP error ${e.statusCode}: ${e.message ?: "Unknown error"}"
            }
            emit(SyncResult.Error(errorMsg))
        } catch (e: Exception) {
            // Log full error for debugging
            android.util.Log.e("CalendarSync", "Unexpected error: ${e.javaClass.simpleName} - ${e.message}", e)
            e.printStackTrace()
            val errorDetails = buildString {
                append("Error during sync: ")
                append(e.javaClass.simpleName)
                if (e.message != null) {
                    append(" - ${e.message}")
                }
                if (e.cause != null) {
                    append(" (caused by: ${e.cause?.javaClass?.simpleName})")
                }
            }
            emit(SyncResult.Error(errorDetails))
        }
    }

    /**
     * Syncs events from local database to Google Calendar (push).
     */
    suspend fun syncToGoogle(event: Event): Flow<SyncResult> = flow {
        try {
            emit(SyncResult.Progress("Syncing event to Google Calendar..."))

            var credential = credentialProvider.getCredential()
                ?: throw IllegalStateException("Not authenticated. Please sign in to Google.")

            // Refresh token if needed (in case it expired)
            if (credentialProvider is CredentialProviderImpl) {
                credentialProvider.refreshAccessToken()
                // Get credential again with refreshed token
                credential = credentialProvider.getCredential()
                    ?: throw IllegalStateException("Failed to get credential after token refresh.")
            }

            // Execute API call on IO dispatcher to avoid NetworkOnMainThreadException
            withContext(Dispatchers.IO) {
                googleCalendarApi.createEvent(
                    credential = credential,
                    title = event.title,
                    description = event.description,
                    startDateTime = event.startDateTime,
                    endDateTime = event.endDateTime
                )
            }

            emit(SyncResult.Success("Event '${event.title}' synced to Google Calendar"))
        } catch (e: IllegalStateException) {
            android.util.Log.e("CalendarSync", "Authentication error: ${e.message}", e)
            emit(SyncResult.Error(e.message ?: "Authentication error. Please sign in again."))
        } catch (e: android.os.NetworkOnMainThreadException) {
            android.util.Log.e("CalendarSync", "NetworkOnMainThreadException: API call must be on background thread", e)
            emit(SyncResult.Error("Synchronization error: Network operation cannot run on main thread. Please try again."))
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            // Google API specific errors
            android.util.Log.e("CalendarSync", "Google API error: ${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                401 -> "Authentication failed. Please sign in again."
                403 -> "Access denied. Please check Calendar permission in Google settings."
                404 -> "Calendar not found. Please check your Google Calendar."
                429 -> "Too many requests. Please try again later."
                else -> "Google Calendar API error: ${e.statusCode} - ${e.message ?: "Unknown error"}"
            }
            emit(SyncResult.Error(errorMsg))
        } catch (e: java.io.IOException) {
            android.util.Log.e("CalendarSync", "Network error: ${e.message}", e)
            emit(SyncResult.Error("Network error: ${e.message ?: "Unable to connect to Google Calendar. Please check your internet connection."}"))
        } catch (e: com.google.api.client.http.HttpResponseException) {
            // HTTP response errors
            android.util.Log.e("CalendarSync", "HTTP error: ${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                401 -> "Authentication failed. Please sign in again."
                403 -> "Access denied. Please check Calendar permission."
                404 -> "Calendar not found."
                500, 503 -> "Google Calendar service unavailable. Please try again later."
                else -> "HTTP error ${e.statusCode}: ${e.message ?: "Unknown error"}"
            }
            emit(SyncResult.Error(errorMsg))
        } catch (e: Exception) {
            // Log full error for debugging
            android.util.Log.e("CalendarSync", "Unexpected error: ${e.javaClass.simpleName} - ${e.message}", e)
            e.printStackTrace()
            val errorDetails = buildString {
                append("Error during sync: ")
                append(e.javaClass.simpleName)
                if (e.message != null) {
                    append(" - ${e.message}")
                }
                if (e.cause != null) {
                    append(" (caused by: ${e.cause?.javaClass?.simpleName})")
                }
            }
            emit(SyncResult.Error(errorDetails))
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

