package com.coparently.app.data.sync

import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.google.CredentialProvider
import com.coparently.app.data.remote.google.CredentialProviderImpl
import com.coparently.app.data.remote.google.GoogleCalendarApi
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.repository.UserRepository
import com.google.api.client.auth.oauth2.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import com.google.api.services.calendar.model.Event as GoogleEvent

/**
 * Repository for synchronizing events between local database and Google Calendar.
 */
@Singleton
class CalendarSyncRepository @Inject constructor(
    private val eventDao: EventDao,
    private val googleCalendarApi: GoogleCalendarApi,
    private val credentialProvider: CredentialProvider,
    private val userRepository: UserRepository,
    private val crashlyticsManager: CrashlyticsManager
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
                ?: throw IllegalStateException("Not authenticated. Please sign in to Google.")
            // Token refresh is now handled automatically in getCredential()

            // A Google Calendar import is created by whoever pulled it in - the same "yours by
            // default" rule AddEditEventScreen applies. UserRepository is the domain interface
            // for exactly this lookup (no pairing subscription, just this device's own Room
            // row); the equivalent UI-layer helper is `ParentsSource.signedInSlot()`, which this
            // class used to reach for instead, across a layer boundary it had no business
            // crossing. Resolved once per sync, not once per event.
            val ownerUid = userRepository.getCurrentUserId()
                ?: throw IllegalStateException("Not signed in. Please sign in to CoPlanly.")
            val ownerSlot = userRepository.getUserById(ownerUid)
                ?.role
                ?: throw IllegalStateException("Not signed in. Please sign in to CoPlanly.")

            emit(SyncResult.Progress("Fetching events from Google Calendar..."))

            // Execute API call on IO dispatcher to avoid NetworkOnMainThreadException
            val imported = withContext(Dispatchers.IO) {
                googleCalendarApi.listEvents(
                    credential = credential,
                    timeMin = startDate,
                    timeMax = endDate
                )
            }

            emit(SyncResult.Progress("Found ${imported.events.size} events in Google Calendar"))

            val eventsToInsert = mutableListOf<EventEntity>()

            imported.events.forEach { googleEvent ->
                val eventEntity = googleEvent.toEventEntity(ownerSlot, ownerUid)
                eventsToInsert.add(eventEntity)
            }

            if (eventsToInsert.isNotEmpty()) {
                eventDao.insertEvents(eventsToInsert)
            }

            // Says which window was read and whether anything was left behind. "Synced N events"
            // on its own is what a truncated import used to say too, which is how a half-finished
            // import passed for a complete one.
            val window = "${imported.from.toLocalDate()} - ${imported.until.toLocalDate()}"
            emit(
                SyncResult.Success(
                    if (imported.truncated) {
                        "Synced the first ${eventsToInsert.size} events ($window). " +
                            "There are more in that period than one import can take."
                    } else {
                        "Synced ${eventsToInsert.size} events ($window)"
                    }
                )
            )
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
        } catch (e: com.google.api.client.http.HttpResponseException) {
            // HTTP response errors. This MUST precede the IOException branch below —
            // HttpResponseException extends IOException, so the reverse order (which shipped)
            // made every 401/403/404/500 here unreachable and surfaced as a generic
            // "Network error". Kotlin does not flag an unreachable catch the way Java does.
            android.util.Log.e("CalendarSync", "HTTP error: ${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                401 -> "Authentication failed. Please sign in again."
                403 -> "Access denied. Please check Calendar permission."
                404 -> "Calendar not found."
                500, 503 -> "Google Calendar service unavailable. Please try again later."
                else -> "HTTP error ${e.statusCode}: ${e.message ?: "Unknown error"}"
            }
            emit(SyncResult.Error(errorMsg))
        } catch (e: java.io.IOException) {
            android.util.Log.e("CalendarSync", "Network error: ${e.message}", e)
            emit(SyncResult.Error("Network error: ${e.message ?: "Unable to connect to Google Calendar. Please check your internet connection."}"))
        } catch (e: Exception) {
            // Log full error for debugging
            android.util.Log.e("CalendarSync", "Unexpected error: ${e.javaClass.simpleName} - ${e.message}", e)
            crashlyticsManager.recordException(e)
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

            val credential = credentialProvider.getCredential()
                ?: throw IllegalStateException("Not authenticated. Please sign in to Google.")
            // Token refresh is now handled automatically in getCredential()

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
        } catch (e: com.google.api.client.http.HttpResponseException) {
            // HTTP response errors. This MUST precede the IOException branch below —
            // HttpResponseException extends IOException, so the reverse order (which shipped)
            // made every 401/403/404/500 here unreachable and surfaced as a generic
            // "Network error". Kotlin does not flag an unreachable catch the way Java does.
            android.util.Log.e("CalendarSync", "HTTP error: ${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                401 -> "Authentication failed. Please sign in again."
                403 -> "Access denied. Please check Calendar permission."
                404 -> "Calendar not found."
                500, 503 -> "Google Calendar service unavailable. Please try again later."
                else -> "HTTP error ${e.statusCode}: ${e.message ?: "Unknown error"}"
            }
            emit(SyncResult.Error(errorMsg))
        } catch (e: java.io.IOException) {
            android.util.Log.e("CalendarSync", "Network error: ${e.message}", e)
            emit(SyncResult.Error("Network error: ${e.message ?: "Unable to connect to Google Calendar. Please check your internet connection."}"))
        } catch (e: Exception) {
            // Log full error for debugging
            android.util.Log.e("CalendarSync", "Unexpected error: ${e.javaClass.simpleName} - ${e.message}", e)
            crashlyticsManager.recordException(e)
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
     *
     * @param ownerSlot This device's own slot, attributed to the import - see the call site in
     *   [syncFromGoogle] for why it isn't looked up per event.
     * @param ownerUid This device's Firebase UID, stamped as `createdByFirebaseUid` so the
     *   imported row can actually be uploaded: the Firestore create rule requires
     *   `createdByFirebaseUid == auth.uid`, and a null here made every import a doomed write
     *   that `getUnsyncedEvents()` retried on every sync forever.
     */
    private fun GoogleEvent.toEventEntity(ownerSlot: String, ownerUid: String): EventEntity {
        // An all-day event carries its date in `date`, not `dateTime`. Reading only `dateTime`
        // and falling back to now() stamped a birthday, a school holiday or an all-day custody
        // note onto today's cell and lost its real date. `date` is a date-only value at UTC
        // midnight, so it is read in UTC (not the system zone, which could shift the day).
        val startDateTime = start?.dateTime?.value?.let { epochMillisToLocal(it) }
            ?: start?.date?.value?.let { utcMillisToLocalDate(it) }
            ?: LocalDateTime.now()

        val endDateTime = end?.dateTime?.value?.let { epochMillisToLocal(it) }
            ?: end?.date?.value?.let { utcMillisToLocalDate(it) }

        return EventEntity(
            id = id ?: java.util.UUID.randomUUID().toString(),
            title = summary ?: "Untitled Event",
            description = description,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            eventType = "google",
            parentOwner = ownerSlot,
            isRecurring = recurrence != null,
            recurrencePattern = recurrence?.firstOrNull()?.toString(),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
            createdByFirebaseUid = ownerUid
        )
    }

    /** A timed event's epoch millis as a local date-time in this device's zone. */
    private fun epochMillisToLocal(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    /** An all-day event's UTC-midnight millis as the start of that calendar day. */
    private fun utcMillisToLocalDate(millis: Long): LocalDateTime =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay()
}


/**
 * Result of synchronization operation.
 */
sealed class SyncResult {
    data class Progress(val message: String) : SyncResult()
    data class Success(val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

