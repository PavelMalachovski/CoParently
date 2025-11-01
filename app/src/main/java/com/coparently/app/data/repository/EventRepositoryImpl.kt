package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of EventRepository.
 * Maps between domain models (Event) and data layer entities (EventEntity).
 * Integrates Firebase Firestore for multi-user sync.
 */
@Singleton
class EventRepositoryImpl @Inject constructor(
    private val eventDao: EventDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreEventDataSource: FirestoreEventDataSource
) : EventRepository {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getAllEvents(): Flow<List<Event>> {
        return eventDao.getAllEvents().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getEventsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        return eventDao.getEventsByDateRange(start, end).map { entities ->
            entities.map { it.toDomain() }
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
        val entity = event.toEntity()
        eventDao.insertEvent(entity)

        // Sync to Firestore if authenticated
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null && !event.syncedToFirestore) {
            val eventData = mapOf(
                "id" to event.id,
                "title" to event.title,
                "description" to (event.description ?: ""),
                "startDateTime" to event.startDateTime.format(dateFormatter),
                "endDateTime" to (event.endDateTime?.format(dateFormatter) ?: ""),
                "eventType" to event.eventType,
                "parentOwner" to event.parentOwner,
                "isRecurring" to event.isRecurring,
                "recurrencePattern" to (event.recurrencePattern ?: ""),
                "createdAt" to event.createdAt.format(dateFormatter),
                "updatedAt" to event.updatedAt.format(dateFormatter),
                "createdByFirebaseUid" to firebaseUser.uid
            )
            firestoreEventDataSource.insertEvent(event.id, eventData)

            // Mark as synced
            val syncedEvent = event.copy(syncedToFirestore = true, createdByFirebaseUid = firebaseUser.uid)
            eventDao.updateEvent(syncedEvent.toEntity())
        }
    }

    override suspend fun updateEvent(event: Event) {
        val entity = event.toEntity()
        eventDao.updateEvent(entity)

        // Sync to Firestore if authenticated
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            val eventData = mapOf(
                "id" to event.id,
                "title" to event.title,
                "description" to (event.description ?: ""),
                "startDateTime" to event.startDateTime.format(dateFormatter),
                "endDateTime" to (event.endDateTime?.format(dateFormatter) ?: ""),
                "eventType" to event.eventType,
                "parentOwner" to event.parentOwner,
                "isRecurring" to event.isRecurring,
                "recurrencePattern" to (event.recurrencePattern ?: ""),
                "createdAt" to event.createdAt.format(dateFormatter),
                "updatedAt" to event.updatedAt.format(dateFormatter),
                "createdByFirebaseUid" to (event.createdByFirebaseUid ?: firebaseUser.uid)
            )
            firestoreEventDataSource.updateEvent(event.id, eventData)
        }
    }

    override suspend fun deleteEvent(event: Event) {
        val entity = event.toEntity()
        eventDao.deleteEvent(entity)

        // Delete from Firestore if authenticated
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null && event.syncedToFirestore) {
            firestoreEventDataSource.deleteEvent(event.id)
        }
    }

    override suspend fun deleteEventById(id: String) {
        val event = eventDao.getEventById(id)
        event?.let { deleteEvent(it.toDomain()) } ?: eventDao.deleteEventById(id)
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        // Fetch unsynced events from local database
        val allEvents = eventDao.getAllEvents()
        allEvents.collect { entities ->
            entities.forEach { entity ->
                if (!entity.syncedToFirestore) {
                    val event = entity.toDomain()
                    val eventData = mapOf(
                        "id" to event.id,
                        "title" to event.title,
                        "description" to (event.description ?: ""),
                        "startDateTime" to event.startDateTime.format(dateFormatter),
                        "endDateTime" to (event.endDateTime?.format(dateFormatter) ?: ""),
                        "eventType" to event.eventType,
                        "parentOwner" to event.parentOwner,
                        "isRecurring" to event.isRecurring,
                        "recurrencePattern" to (event.recurrencePattern ?: ""),
                        "createdAt" to event.createdAt.format(dateFormatter),
                        "updatedAt" to event.updatedAt.format(dateFormatter),
                        "createdByFirebaseUid" to firebaseUser.uid
                    )
                    firestoreEventDataSource.insertEvent(event.id, eventData)

                    // Mark as synced
                    val syncedEvent = event.copy(syncedToFirestore = true, createdByFirebaseUid = firebaseUser.uid)
                    eventDao.updateEvent(syncedEvent.toEntity())
                }
            }
        }
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
            createdByFirebaseUid = createdByFirebaseUid
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
            createdByFirebaseUid = createdByFirebaseUid
        )
    }
}

