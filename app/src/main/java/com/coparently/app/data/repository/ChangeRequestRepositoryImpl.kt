package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ChangeRequestDao
import com.coparently.app.data.local.entity.ChangeRequestEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChangeRequestDataSource
import com.coparently.app.data.remote.firebase.PushPayload
import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus
import com.coparently.app.domain.repository.ChangeRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChangeRequestRepositoryImpl @Inject constructor(
    private val changeRequestDao: ChangeRequestDao,
    private val firestoreDataSource: FirestoreChangeRequestDataSource,
    private val firebaseAuthService: FirebaseAuthService,
    private val fcmService: FcmService
) : ChangeRequestRepository {

    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getAllChangeRequests(): Flow<List<ChangeRequest>> {
        return changeRequestDao.getAllChangeRequests().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getPendingIncomingCount(userId: String): Flow<Int> {
        return changeRequestDao.getPendingIncomingCount(userId)
    }

    override fun getChangeRequestsForEvent(eventId: String): Flow<List<ChangeRequest>> {
        return changeRequestDao.getChangeRequestsForEvent(eventId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getChangeRequestById(id: String): ChangeRequest? {
        return changeRequestDao.getChangeRequestById(id)?.toDomain()
    }

    override suspend fun createChangeRequest(request: ChangeRequest) {
        changeRequestDao.insertChangeRequest(request.toEntity())

        if (firebaseAuthService.getCurrentUser() != null) {
            firestoreDataSource.setChangeRequest(request.id, request.toFirestoreMap())
            changeRequestDao.insertChangeRequest(
                request.copy(syncedToFirestore = true).toEntity()
            )
            notifyCounterparty(request, targetUserId = request.requestedTo, action = "created")
        }
    }

    override suspend fun updateStatus(requestId: String, status: ChangeRequestStatus) {
        val request = changeRequestDao.getChangeRequestById(requestId)?.toDomain() ?: return
        val updated = request.copy(status = status, respondedAt = LocalDateTime.now())
        changeRequestDao.insertChangeRequest(updated.toEntity())

        if (firebaseAuthService.getCurrentUser() != null) {
            firestoreDataSource.setChangeRequest(updated.id, updated.toFirestoreMap())
            changeRequestDao.insertChangeRequest(
                updated.copy(syncedToFirestore = true).toEntity()
            )
            // Cancellation notifies the addressee; accept/decline notify the requester.
            val target = if (status == ChangeRequestStatus.CANCELLED) {
                updated.requestedTo
            } else {
                updated.requestedBy
            }
            notifyCounterparty(updated, targetUserId = target, action = status.name.lowercase())
        }
    }

    override suspend fun observeRemote() {
        val userId = firebaseAuthService.getCurrentUser()?.uid ?: return
        firestoreDataSource.observeChangeRequestsForUser(userId)
            // Offline-first: a Firestore failure (missing index, denied read, no network)
            // must not crash the app — Room stays the source of truth. Swallow and log.
            .catch { e ->
                android.util.Log.w("ChangeRequestRepo", "Change request sync failed", e)
            }
            .collect { documents ->
                documents.forEach { data ->
                    runCatching { data.toDomain() }.getOrNull()?.let { remote ->
                        changeRequestDao.insertChangeRequest(
                            remote.copy(syncedToFirestore = true).toEntity()
                        )
                    }
                }
            }
    }

    private suspend fun notifyCounterparty(
        request: ChangeRequest,
        targetUserId: String,
        action: String
    ) {
        if (targetUserId.isEmpty() || targetUserId == firebaseAuthService.getCurrentUser()?.uid) return
        // Null for an action nothing announces — see `FcmService.createEventNotificationPayload`.
        val type = PushPayload.changeRequestType(action) ?: return
        // Empty rather than "Your co-parent": the receiving device holds a translated fallback
        // for a missing name, and an English one written here would win over it.
        val performedBy = firebaseAuthService.getCurrentUser()?.displayName.orEmpty()
        val payload = mapOf(
            PushPayload.TYPE to type,
            PushPayload.CHANGE_REQUEST_ID to request.id,
            PushPayload.EVENT_ID to request.eventId,
            PushPayload.SUBJECT to request.eventTitle,
            PushPayload.ACTOR to performedBy
        )
        // Notification delivery is best-effort; the request itself is already synced.
        fcmService.queueNotificationForUser(targetUserId, payload)
    }

    private fun ChangeRequest.toFirestoreMap(): Map<String, Any> = mapOf(
        "id" to id,
        "eventId" to eventId,
        "eventTitle" to eventTitle,
        "requestedBy" to requestedBy,
        "requestedTo" to requestedTo,
        "currentStartDateTime" to currentStartDateTime.format(dateTimeFormatter),
        "currentEndDateTime" to (currentEndDateTime?.format(dateTimeFormatter) ?: ""),
        "proposedStartDateTime" to proposedStartDateTime.format(dateTimeFormatter),
        "proposedEndDateTime" to (proposedEndDateTime?.format(dateTimeFormatter) ?: ""),
        "note" to (note ?: ""),
        "status" to status.name,
        "createdAt" to createdAt.format(dateTimeFormatter),
        "respondedAt" to (respondedAt?.format(dateTimeFormatter) ?: "")
    )

    private fun Map<String, Any>.toDomain(): ChangeRequest = ChangeRequest(
        id = this["id"] as String,
        eventId = this["eventId"] as String,
        eventTitle = this["eventTitle"] as? String ?: "",
        requestedBy = this["requestedBy"] as String,
        requestedTo = this["requestedTo"] as String,
        currentStartDateTime = LocalDateTime.parse(this["currentStartDateTime"] as String, dateTimeFormatter),
        currentEndDateTime = (this["currentEndDateTime"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { LocalDateTime.parse(it, dateTimeFormatter) },
        proposedStartDateTime = LocalDateTime.parse(this["proposedStartDateTime"] as String, dateTimeFormatter),
        proposedEndDateTime = (this["proposedEndDateTime"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { LocalDateTime.parse(it, dateTimeFormatter) },
        note = (this["note"] as? String)?.takeIf { it.isNotEmpty() },
        status = ChangeRequestStatus.valueOf(this["status"] as String),
        createdAt = LocalDateTime.parse(this["createdAt"] as String, dateTimeFormatter),
        respondedAt = (this["respondedAt"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { LocalDateTime.parse(it, dateTimeFormatter) }
    )

    private fun ChangeRequestEntity.toDomain(): ChangeRequest = ChangeRequest(
        id = id,
        eventId = eventId,
        eventTitle = eventTitle,
        requestedBy = requestedBy,
        requestedTo = requestedTo,
        currentStartDateTime = currentStartDateTime,
        currentEndDateTime = currentEndDateTime,
        proposedStartDateTime = proposedStartDateTime,
        proposedEndDateTime = proposedEndDateTime,
        note = note,
        status = ChangeRequestStatus.valueOf(status),
        createdAt = createdAt,
        respondedAt = respondedAt,
        syncedToFirestore = syncedToFirestore
    )

    private fun ChangeRequest.toEntity(): ChangeRequestEntity = ChangeRequestEntity(
        id = id,
        eventId = eventId,
        eventTitle = eventTitle,
        requestedBy = requestedBy,
        requestedTo = requestedTo,
        currentStartDateTime = currentStartDateTime,
        currentEndDateTime = currentEndDateTime,
        proposedStartDateTime = proposedStartDateTime,
        proposedEndDateTime = proposedEndDateTime,
        note = note,
        status = status.name,
        createdAt = createdAt,
        respondedAt = respondedAt,
        syncedToFirestore = syncedToFirestore
    )
}
