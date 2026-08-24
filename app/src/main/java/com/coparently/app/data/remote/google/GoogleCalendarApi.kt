package com.coparently.app.data.remote.google

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event as GoogleCalendarEvent
import com.google.api.services.calendar.model.EventDateTime
import com.google.api.services.calendar.model.Events
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Calendar API wrapper for Android.
 * Provides methods to interact with Google Calendar API.
 */
@Singleton
class GoogleCalendarApi @Inject constructor() {
    companion object {
        private val SCOPES = listOf(CalendarScopes.CALENDAR)
        private val APPLICATION_NAME = "CoPlanly"
        private val JSON_FACTORY = GsonFactory.getDefaultInstance()
        private val HTTP_TRANSPORT = NetHttpTransport()

        /** Google's own per-page ceiling is 2500; 250 keeps any single request small. */
        private const val PAGE_SIZE = 250

        /**
         * How many events one import may take. Reached only by a very full calendar, and when it
         * is reached the caller is told — the point of [CalendarEvents.truncated].
         */
        const val IMPORT_LIMIT = 2_000

        /**
         * How far ahead an import reaches when the caller names no end.
         *
         * A horizon is not a nicety here. `setSingleEvents(true)` expands recurrences into
         * instances, so "everything from now on" is not a finite request at all — a single
         * open-ended weekly event generates instances forever, and without an end date the
         * event cap alone would silently decide where the calendar stopped. One year is a
         * co-parenting horizon: school years, holidays and custody rotations all fit inside it.
         */
        private const val HORIZON_MONTHS = 12L

        /** Refuses to page forever if a server ever returns a token that does not advance. */
        private const val MAX_PAGES = 64
    }

    /**
     * Gets Google Calendar service instance.
     */
    fun getCalendarService(credential: Credential): Calendar {
        return Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * Lists events from Google Calendar, following every page.
     *
     * The previous version asked for one page of 50 and returned it, while its only caller passed
     * no bounds at all — so an import of a real calendar stopped at the 50th event and reported
     * "Found 50 events", which is what a complete import also looks like. Nothing said otherwise.
     *
     * Two bounds now exist and both are reported rather than assumed: the window
     * ([CalendarEvents.from]/[CalendarEvents.until], defaulting to the next [HORIZON_MONTHS]
     * months) and the event cap ([IMPORT_LIMIT], surfaced as [CalendarEvents.truncated]).
     *
     * @param limit Most events to return. Reaching it sets [CalendarEvents.truncated].
     */
    @Throws(IOException::class)
    fun listEvents(
        credential: Credential,
        calendarId: String = "primary",
        timeMin: LocalDateTime? = null,
        timeMax: LocalDateTime? = null,
        limit: Int = IMPORT_LIMIT
    ): CalendarEvents {
        val calendar = getCalendarService(credential)
        val from = timeMin ?: LocalDateTime.now()
        val until = timeMax ?: from.plusMonths(HORIZON_MONTHS)

        val paged = collectPages(limit, MAX_PAGES) { pageToken ->
            val response: Events = calendar.events().list(calendarId)
                .setTimeMin(from.toGoogleDateTime())
                .setTimeMax(until.toGoogleDateTime())
                .setMaxResults(PAGE_SIZE)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setPageToken(pageToken)
                .execute()
            PageOf(response.items.orEmpty(), response.nextPageToken)
        }

        return CalendarEvents(
            events = paged.items,
            truncated = paged.truncated,
            from = from,
            until = until
        )
    }

    private fun LocalDateTime.toGoogleDateTime(): DateTime =
        DateTime(atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /**
     * Creates an event in Google Calendar.
     */
    @Throws(IOException::class)
    fun createEvent(
        credential: Credential,
        calendarId: String = "primary",
        title: String,
        description: String?,
        startDateTime: LocalDateTime,
        endDateTime: LocalDateTime?
    ): GoogleCalendarEvent {
        val calendar = getCalendarService(credential)

        val event = GoogleCalendarEvent()
            .setSummary(title)
            .apply { description?.let { setDescription(it) } }

        val start = EventDateTime()
            .setDateTime(DateTime(startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            .setTimeZone(ZoneId.systemDefault().id)
        event.start = start

        val end = EventDateTime()
            .setDateTime(
                DateTime((endDateTime ?: startDateTime.plusHours(1))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            )
            .setTimeZone(ZoneId.systemDefault().id)
        event.end = end

        return calendar.events().insert(calendarId, event).execute()
    }

    /**
     * Updates an event in Google Calendar.
     */
    @Throws(IOException::class)
    fun updateEvent(
        credential: Credential,
        calendarId: String = "primary",
        eventId: String,
        title: String,
        description: String?,
        startDateTime: LocalDateTime,
        endDateTime: LocalDateTime?
    ): GoogleCalendarEvent {
        val calendar = getCalendarService(credential)

        // Get existing event
        val event = calendar.events().get(calendarId, eventId).execute()

        // Update event fields
        event.summary = title
        description?.let { event.description = it }

        val start = EventDateTime()
            .setDateTime(DateTime(startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            .setTimeZone(ZoneId.systemDefault().id)
        event.start = start

        val end = EventDateTime()
            .setDateTime(
                DateTime((endDateTime ?: startDateTime.plusHours(1))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            )
            .setTimeZone(ZoneId.systemDefault().id)
        event.end = end

        return calendar.events().update(calendarId, eventId, event).execute()
    }

    /**
     * Deletes an event from Google Calendar.
     */
    @Throws(IOException::class)
    fun deleteEvent(
        credential: Credential,
        calendarId: String = "primary",
        eventId: String
    ) {
        val calendar = getCalendarService(credential)
        calendar.events().delete(calendarId, eventId).execute()
    }
}

/**
 * Provides Credential for Google Calendar API.
 * This interface should be implemented to provide credentials.
 */
interface CredentialProvider {
    /**
     * The Google credential for this device, refreshing the access token if it has expired.
     *
     * `suspend` because obtaining one can perform a **network** refresh. It used to be a plain
     * function whose implementation wrapped that refresh in `runBlocking`, and
     * `CalendarSyncRepository` called it from `viewModelScope` — on the main thread — before
     * entering its own `withContext(Dispatchers.IO)` a few lines later. Enabling Google
     * Calendar sync on a slow connection therefore blocked the UI thread for the length of an
     * OAuth round-trip.
     */
    suspend fun getCredential(): Credential?
}

