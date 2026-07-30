package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.repository.EventRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of EventRepository.
 * Maps between domain models (Event) and data layer entities (EventEntity).
 * Integrates Firebase Firestore for multi-user sync.
 *
 * Private events (isPrivate = true) are never pushed to Firestore, so they
 * stay visible only on the creator's device.
 */
@Singleton
class EventRepositoryImpl @Inject constructor(
    private val eventDao: EventDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreEventDataSource: FirestoreEventDataSource
) : EventRepository {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val dateOnlyFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val gson = Gson()

    override fun getAllEvents(): Flow<List<Event>> {
        return eventDao.getAllEvents().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getEventsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        // Non-recurring events matched by the query + recurring events expanded
        // into their occurrences within the range.
        return kotlinx.coroutines.flow.combine(
            eventDao.getSingleEventsByDateRange(start, end),
            eventDao.getRecurringEventsStartedBefore(end)
        ) { single, recurring ->
            val singleEvents = single.map { it.toDomain() }
            val occurrences = com.coparently.app.domain.usecase.RecurrenceExpander.expandAll(
                recurring.map { it.toDomain() },
                start,
                end
            )
            (singleEvents + occurrences).sortedBy { it.startDateTime }
        }
    }

    override fun getEventsByDate(date: LocalDateTime): Flow<List<Event>> {
        return eventDao.getEventsByDate(date).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getEventById(id: String): Event? {
        return eventDao.getEventById(id)?.toDomain()
    }

    override fun getEventsByParent(parentOwner: String): Flow<List<Event>> {
        return eventDao.getEventsByParent(parentOwner).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun insertEvent(event: Event) {
        eventDao.insertEvent(event.toEntity())

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null && !event.syncedToFirestore && !event.isPrivate) {
            firestoreEventDataSource.insertEvent(event.id, event.toFirestoreMap(firebaseUser.uid))

            val syncedEvent = event.copy(syncedToFirestore = true, createdByFirebaseUid = firebaseUser.uid)
            eventDao.updateEvent(syncedEvent.toEntity())
        }
    }

    override suspend fun updateEvent(event: Event) {
        eventDao.updateEvent(event.toEntity())

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        if (event.isPrivate) {
            // Event turned private after being shared: remove the remote copy
            if (event.syncedToFirestore) {
                firestoreEventDataSource.deleteEvent(event.id)
                eventDao.updateEvent(event.copy(syncedToFirestore = false).toEntity())
            }
        } else {
            val uid = event.createdByFirebaseUid ?: firebaseUser.uid
            firestoreEventDataSource.updateEvent(event.id, event.toFirestoreMap(uid))
        }
    }

    override suspend fun deleteEvent(event: Event) {
        eventDao.deleteEvent(event.toEntity())

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null && event.syncedToFirestore) {
            // The event is already gone locally. A rejected remote delete arrives from
            // Firestore's write-rejection path and kills the app if nothing catches it —
            // the same failure the expense add path was hardened against.
            try {
                firestoreEventDataSource.deleteEvent(event.id)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("EventRepo", "Event Firestore delete failed", e)
            }
        }
    }

    override suspend fun deleteEventById(id: String) {
        val event = eventDao.getEventById(id)
        event?.let { deleteEvent(it.toDomain()) } ?: eventDao.deleteEventById(id)
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        // Take a single snapshot; collecting the flow here would never complete
        val entities = eventDao.getAllEvents().first()
        entities.forEach { entity ->
            if (!entity.syncedToFirestore && !entity.isPrivate) {
                val event = entity.toDomain()
                firestoreEventDataSource.insertEvent(event.id, event.toFirestoreMap(firebaseUser.uid))

                val syncedEvent = event.copy(syncedToFirestore = true, createdByFirebaseUid = firebaseUser.uid)
                eventDao.updateEvent(syncedEvent.toEntity())
            }
        }
    }

    /**
     * Builds the Firestore document map for this event.
     * Single source of truth for the remote schema.
     */
    private fun Event.toFirestoreMap(creatorUid: String): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "title" to title,
            "description" to (description ?: ""),
            "startDateTime" to startDateTime.format(dateFormatter),
            "endDateTime" to (endDateTime?.format(dateFormatter) ?: ""),
            "eventType" to eventType,
            "parentOwner" to parentOwner,
            "isRecurring" to isRecurring,
            "recurrencePattern" to (recurrencePattern ?: ""),
            "recurrenceEndDate" to (recurrenceEndDate?.format(dateOnlyFormatter) ?: ""),
            "pickupConfirmedBy" to (pickupConfirmedBy ?: ""),
            "pickupConfirmedAt" to (pickupConfirmedAt?.format(dateFormatter) ?: ""),
            "createdAt" to createdAt.format(dateFormatter),
            "updatedAt" to updatedAt.format(dateFormatter),
            "createdByFirebaseUid" to creatorUid,
            "sharedWith" to sharedWith,
            "lastModifiedBy" to (lastModifiedBy ?: creatorUid),
            "permissions" to permissions,
            "imageUrl" to (imageUrl ?: "")
        )
    }

    /**
     * Maps EventEntity to Event domain model.
     */
    private fun EventEntity.toDomain(): Event {
        return Event(
            id = id,
            title = title,
            description = description,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            eventType = eventType,
            parentOwner = parentOwner,
            isRecurring = isRecurring,
            recurrencePattern = recurrencePattern,
            createdAt = createdAt,
            updatedAt = updatedAt,
            syncedToFirestore = syncedToFirestore,
            createdByFirebaseUid = createdByFirebaseUid,
            sharedWith = runCatching {
                gson.fromJson(sharedWithJson, Array<String>::class.java)?.toList()
            }.getOrNull() ?: emptyList(),
            lastModifiedBy = lastModifiedBy,
            permissions = permissions,
            isPrivate = isPrivate,
            recurrenceEndDate = recurrenceEndDate,
            pickupConfirmedBy = pickupConfirmedBy,
            pickupConfirmedAt = pickupConfirmedAt,
            reminderMinutes = reminderMinutes,
            imageUrl = imageUrl
        )
    }

    /**
     * Maps Event domain model to EventEntity.
     */
    private fun Event.toEntity(): EventEntity {
        return EventEntity(
            id = id,
            title = title,
            description = description,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            eventType = eventType,
            parentOwner = parentOwner,
            isRecurring = isRecurring,
            recurrencePattern = recurrencePattern,
            createdAt = createdAt,
            updatedAt = updatedAt,
            syncedToFirestore = syncedToFirestore,
            createdByFirebaseUid = createdByFirebaseUid,
            sharedWithJson = gson.toJson(sharedWith),
            lastModifiedBy = lastModifiedBy,
            permissions = permissions,
            isPrivate = isPrivate,
            recurrenceEndDate = recurrenceEndDate,
            pickupConfirmedBy = pickupConfirmedBy,
            pickupConfirmedAt = pickupConfirmedAt,
            reminderMinutes = reminderMinutes,
            imageUrl = imageUrl
        )
    }
}
