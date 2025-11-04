package com.coparently.app.data.sync

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing synchronization between local database and Firestore.
 * Handles bidirectional sync of events, child info, and user data.
 * Provides real-time sync status updates.
 */
@Singleton
class SyncService @Inject constructor(
    private val eventDao: EventDao,
    private val childInfoDao: ChildInfoDao,
    private val userDao: UserDao,
    private val firestoreEventDataSource: FirestoreEventDataSource,
    private val firestoreChildInfoDataSource: FirestoreChildInfoDataSource,
    private val firestoreUserDataSource: FirestoreUserDataSource,
    private val firebaseAuthService: FirebaseAuthService,
    private val fcmService: FcmService
) {
    private val gson = Gson()
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * Performs full synchronization of all data.
     * Uploads local changes and downloads remote changes.
     */
    suspend fun performFullSync(): Result<Unit> {
        return try {
            val currentUser = firebaseAuthService.getCurrentUser()
                ?: return Result.failure(IllegalStateException("User not authenticated"))

            _syncStatus.value = SyncStatus.Syncing(0, 100)

            // Step 1: Sync user data (including FCM token)
            _syncStatus.value = SyncStatus.Syncing(10, 100)
            syncUserData(currentUser.uid)

            // Step 2: Sync events
            _syncStatus.value = SyncStatus.Syncing(40, 100)
            syncEvents(currentUser.uid)

            // Step 3: Sync child info
            _syncStatus.value = SyncStatus.Syncing(70, 100)
            syncChildInfo(currentUser.uid)

            // Step 4: Complete
            _syncStatus.value = SyncStatus.Success(LocalDateTime.now())
            Result.success(Unit)
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Syncs events between local database and Firestore.
     */
    private suspend fun syncEvents(userId: String) {
        // Upload unsynced local events
        val unsyncedEvents = eventDao.getUnsyncedEvents()

        for (entity in unsyncedEvents) {
            val eventData = mapOf(
                "id" to entity.id,
                "title" to entity.title,
                "description" to entity.description,
                "startDateTime" to entity.startDateTime.format(formatter),
                "endDateTime" to entity.endDateTime?.format(formatter),
                "eventType" to entity.eventType,
                "parentOwner" to entity.parentOwner,
                "isRecurring" to entity.isRecurring,
                "recurrencePattern" to entity.recurrencePattern,
                "createdAt" to entity.createdAt.format(formatter),
                "updatedAt" to entity.updatedAt.format(formatter),
                "createdByFirebaseUid" to entity.createdByFirebaseUid,
                "sharedWith" to gson.fromJson(entity.sharedWithJson, List::class.java),
                "lastModifiedBy" to entity.lastModifiedBy,
                "permissions" to entity.permissions
            )

            val result = firestoreEventDataSource.insertEvent(entity.id, eventData)
            if (result.isSuccess) {
                eventDao.markAsSynced(entity.id)

                // Notify partner if event is shared
                val sharedWith = gson.fromJson(entity.sharedWithJson, Array<String>::class.java).toList()
                for (partnerId in sharedWith) {
                    if (partnerId != userId) {
                        notifyEventUpdate(partnerId, entity.id, entity.title, "created")
                    }
                }
            }
        }

        // Download events from Firestore
        val localUser = userDao.getUserById(userId)
        val partnerId = localUser?.partnerId

        if (partnerId != null) {
            firestoreEventDataSource.observeEventsForParents(listOf(userId, partnerId)).collect { firestoreEvents ->
                for (firestoreData in firestoreEvents) {
                    val eventEntity = firestoreData.toEventEntity()
                    eventDao.insertEvent(eventEntity.copy(syncedToFirestore = true))
                }
            }
        }
    }

    /**
     * Syncs child information between local database and Firestore.
     */
    private suspend fun syncChildInfo(userId: String) {
        // Upload unsynced local child info
        val unsyncedChildInfo = childInfoDao.getUnsyncedChildInfo()

        for (entity in unsyncedChildInfo) {
            val childInfoData = mapOf(
                "id" to entity.id,
                "childName" to entity.childName,
                "dateOfBirth" to entity.dateOfBirth?.format(formatter),
                "medications" to gson.fromJson(entity.medicationsJson, List::class.java),
                "activities" to gson.fromJson(entity.activitiesJson, List::class.java),
                "allergies" to gson.fromJson(entity.allergiesJson, List::class.java),
                "medicalNotes" to entity.medicalNotes,
                "emergencyContacts" to gson.fromJson(entity.emergencyContactsJson, List::class.java),
                "schoolInfo" to entity.schoolInfoJson?.let { gson.fromJson(it, Map::class.java) },
                "createdAt" to entity.createdAt.format(formatter),
                "updatedAt" to entity.updatedAt.format(formatter),
                "createdByFirebaseUid" to entity.createdByFirebaseUid,
                "lastModifiedBy" to entity.lastModifiedBy,
                "sharedWith" to listOfNotNull(entity.createdByFirebaseUid, entity.lastModifiedBy).distinct()
            )

            val result = firestoreChildInfoDataSource.upsertChildInfo(entity.id, childInfoData)
            if (result.isSuccess) {
                childInfoDao.markAsSynced(entity.id)

                // Notify partner
                val localUser = userDao.getUserById(userId)
                val partnerId = localUser?.partnerId
                if (partnerId != null && partnerId != userId) {
                    notifyChildInfoUpdate(partnerId, entity.id, entity.childName)
                }
            }
        }

        // Download child info from Firestore
        firestoreChildInfoDataSource.getChildInfoForParent(userId).collect { firestoreList ->
            for (firestoreData in firestoreList) {
                val childInfoEntity = firestoreData.toChildInfoEntity()
                childInfoDao.insertChildInfo(childInfoEntity.copy(syncedToFirestore = true))
            }
        }
    }

    /**
     * Syncs user data including FCM token.
     */
    private suspend fun syncUserData(userId: String) {
        val localUser = userDao.getUserById(userId) ?: return

        // Update FCM token
        val token = fcmService.getCurrentToken()
        if (token != null && token != localUser.fcmToken) {
            fcmService.updateUserToken(token)
            userDao.updateUser(localUser.copy(fcmToken = token))
        }

        // Download latest user data from Firestore
        val remoteUserData = firestoreUserDataSource.getUserById(userId)
        if (remoteUserData != null) {
            val updatedUser = localUser.copy(
                partnerId = remoteUserData["partnerId"] as? String,
                fcmToken = remoteUserData["fcmToken"] as? String
            )
            userDao.updateUser(updatedUser)
        }
    }

    /**
     * Notifies partner about event update.
     */
    private suspend fun notifyEventUpdate(
        partnerId: String,
        eventId: String,
        eventTitle: String,
        action: String
    ) {
        val currentUser = firebaseAuthService.getCurrentUser() ?: return
        val userData = userDao.getUserById(currentUser.uid) ?: return

        val notificationPayload = fcmService.createEventNotificationPayload(
            eventId = eventId,
            eventTitle = eventTitle,
            action = action,
            performedBy = userData.name
        )

        fcmService.queueNotificationForUser(partnerId, notificationPayload)
    }

    /**
     * Notifies partner about child info update.
     */
    private suspend fun notifyChildInfoUpdate(
        partnerId: String,
        childInfoId: String,
        childName: String
    ) {
        val currentUser = firebaseAuthService.getCurrentUser() ?: return
        val userData = userDao.getUserById(currentUser.uid) ?: return

        val notificationPayload = fcmService.createChildInfoNotificationPayload(
            childInfoId = childInfoId,
            childName = childName,
            updatedBy = userData.name
        )

        fcmService.queueNotificationForUser(partnerId, notificationPayload)
    }

    /**
     * Converts Firestore event data to EventEntity.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toEventEntity(): com.coparently.app.data.local.entity.EventEntity {
        return com.coparently.app.data.local.entity.EventEntity(
            id = this["id"] as String,
            title = this["title"] as String,
            description = this["description"] as? String,
            startDateTime = LocalDateTime.parse(this["startDateTime"] as String, formatter),
            endDateTime = (this["endDateTime"] as? String)?.let { LocalDateTime.parse(it, formatter) },
            eventType = this["eventType"] as String,
            parentOwner = this["parentOwner"] as String,
            isRecurring = this["isRecurring"] as? Boolean ?: false,
            recurrencePattern = this["recurrencePattern"] as? String,
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            syncedToFirestore = true,
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            sharedWithJson = gson.toJson(this["sharedWith"] ?: emptyList<String>()),
            lastModifiedBy = this["lastModifiedBy"] as? String,
            permissions = this["permissions"] as? String ?: "read_write"
        )
    }

    /**
     * Converts Firestore child info data to ChildInfoEntity.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toChildInfoEntity(): com.coparently.app.data.local.entity.ChildInfoEntity {
        return com.coparently.app.data.local.entity.ChildInfoEntity(
            id = this["id"] as String,
            childName = this["childName"] as String,
            dateOfBirth = (this["dateOfBirth"] as? String)?.let { LocalDateTime.parse(it, formatter) },
            medicationsJson = gson.toJson(this["medications"] ?: emptyList<Any>()),
            activitiesJson = gson.toJson(this["activities"] ?: emptyList<Any>()),
            allergiesJson = gson.toJson(this["allergies"] ?: emptyList<String>()),
            medicalNotes = this["medicalNotes"] as? String,
            emergencyContactsJson = gson.toJson(this["emergencyContacts"] ?: emptyList<Any>()),
            schoolInfoJson = (this["schoolInfo"] as? Map<*, *>)?.let { gson.toJson(it) },
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            lastModifiedBy = this["lastModifiedBy"] as? String,
            syncedToFirestore = true
        )
    }
}

/**
 * Represents the current synchronization status.
 */
sealed class SyncStatus {
    /**
     * No sync operation in progress.
     */
    data object Idle : SyncStatus()

    /**
     * Sync operation in progress.
     *
     * @property progress Current progress (0-100)
     * @property total Total items to sync
     */
    data class Syncing(val progress: Int, val total: Int) : SyncStatus()

    /**
     * Sync completed successfully.
     *
     * @property lastSyncTime Time of last successful sync
     */
    data class Success(val lastSyncTime: LocalDateTime) : SyncStatus()

    /**
     * Sync failed with an error.
     *
     * @property message Error message
     */
    data class Error(val message: String) : SyncStatus()
}

