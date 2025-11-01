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
        private val APPLICATION_NAME = "CoParently"
        private val JSON_FACTORY = GsonFactory.getDefaultInstance()
        private val HTTP_TRANSPORT = NetHttpTransport()
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
     * Lists events from Google Calendar.
     */
    @Throws(IOException::class)
    fun listEvents(
        credential: Credential,
        calendarId: String = "primary",
        timeMin: LocalDateTime? = null,
        timeMax: LocalDateTime? = null,
        maxResults: Int = 50
    ): List<GoogleCalendarEvent> {
        val calendar = getCalendarService(credential)
        val now = DateTime(System.currentTimeMillis())

        val timeMinDateTime = timeMin?.let {
            DateTime(it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } ?: now

        val timeMaxDateTime = timeMax?.let {
            DateTime(it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }

        val events: Events = calendar.events().list(calendarId)
            .setTimeMin(timeMinDateTime)
            .apply { timeMaxDateTime?.let { setTimeMax(it) } }
            .setMaxResults(maxResults.toInt())
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()

        return events.items ?: emptyList()
    }

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
    fun getCredential(): Credential?
}

