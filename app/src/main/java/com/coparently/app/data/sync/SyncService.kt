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
    private val fcmService: FcmService,
    private val conflictResolver: ConflictResolver
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
        // Upload unsynced local events; private events never leave the device
        val unsyncedEvents = eventDao.getUnsyncedEvents().filterNot { it.isPrivate }
        val partnerId = userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }

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
                "recurrenceEndDate" to entity.recurrenceEndDate?.toString(),
                "pickupConfirmedBy" to entity.pickupConfirmedBy,
                "pickupConfirmedAt" to entity.pickupConfirmedAt?.format(formatter),
                "createdAt" to entity.createdAt.format(formatter),
                "updatedAt" to entity.updatedAt.format(formatter),
                "createdByFirebaseUid" to entity.createdByFirebaseUid,
                "sharedWith" to shareTargets(entity.sharedWithJson, userId, partnerId),
                "lastModifiedBy" to entity.lastModifiedBy,
                "permissions" to entity.permissions,
                "imageUrl" to entity.imageUrl
            )

            val result = firestoreEventDataSource.insertEvent(entity.id, eventData)
            if (result.isSuccess) {
                eventDao.markAsSynced(entity.id)

                // Notify everyone the event is shared with, except the uploader
                for (recipientId in shareTargets(entity.sharedWithJson, userId, partnerId)) {
                    if (recipientId != userId) {
                        notifyEventUpdate(recipientId, entity.id, entity.title, "created")
                    }
                }
            }
        }

        // Download every event shared with this user (own + co-parent's). Unlike the
        // previous partner-gated block this runs whether or not the account is paired:
        // the query is authorized by `sharedWith` alone, so there is nothing to gate on.
        firestoreEventDataSource.observeEventsSharedWith(userId).collect { firestoreEvents ->
            for (firestoreData in firestoreEvents) {
                val remoteEntity = firestoreData.toEventEntity()
                val localEntity = eventDao.getEventById(remoteEntity.id)

                if (localEntity != null && !localEntity.syncedToFirestore) {
                    // Conflict detected - resolve it
                    val resolution = conflictResolver.resolveEventConflict(
                        local = localEntity,
                        remote = remoteEntity,
                        currentUserId = userId
                    )

                    when (resolution) {
                        is ConflictResolution.UseLocal -> {
                            // Keep local, upload to remote. This is a partial `update()`,
                            // so `sharedWith` on the remote document is left as it is
                            // rather than being narrowed to this device's stale copy.
                            val localData = mapOf(
                                "id" to localEntity.id,
                                "title" to localEntity.title,
                                "description" to localEntity.description,
                                "startDateTime" to localEntity.startDateTime.format(formatter),
                                "endDateTime" to localEntity.endDateTime?.format(formatter),
                                "eventType" to localEntity.eventType,
                                "parentOwner" to localEntity.parentOwner,
                                "isRecurring" to localEntity.isRecurring,
                                "recurrencePattern" to localEntity.recurrencePattern,
                                "recurrenceEndDate" to localEntity.recurrenceEndDate?.toString(),
                                "pickupConfirmedBy" to localEntity.pickupConfirmedBy,
                                "pickupConfirmedAt" to localEntity.pickupConfirmedAt?.format(formatter),
                                "createdAt" to localEntity.createdAt.format(formatter),
                                "updatedAt" to LocalDateTime.now().format(formatter),
                                "createdByFirebaseUid" to localEntity.createdByFirebaseUid,
                                "lastModifiedBy" to userId,
                                "imageUrl" to localEntity.imageUrl
                            )
                            firestoreEventDataSource.updateEvent(localEntity.id, localData)
                            eventDao.markAsSynced(localEntity.id)
                        }
                        is ConflictResolution.UseRemote -> {
                            // Use remote version
                            eventDao.insertEvent(remoteEntity.copy(syncedToFirestore = true))
                        }
                        is ConflictResolution.Merged -> {
                            // Future: handle merged data
                            eventDao.insertEvent(resolution.data.copy(syncedToFirestore = true))
                        }
                    }
                } else {
                    // No conflict - just insert/update
                    eventDao.insertEvent(remoteEntity.copy(syncedToFirestore = true))
                }
            }
        }
    }

    /**
     * Resolves the `sharedWith` audience for an event upload.
     *
     * The stored [sharedWithJson] is honoured and only ever widened — never narrowed — with
     * the uploader and their co-parent. Both must be present because the `events` read rule
     * and [FirestoreEventDataSource.observeEventsSharedWith] are both keyed on this list: an
     * event missing from it is invisible to the parent it belongs to, and the whole down-sync
     * is only meaningful once co-parent events actually carry the reader's UID.
     *
     * @param sharedWithJson The entity's stored JSON array of Firebase UIDs.
     * @param userId The uploading user's Firebase UID.
     * @param partnerId The co-parent's Firebase UID, or null when unpaired.
     */
    private fun shareTargets(sharedWithJson: String, userId: String, partnerId: String?): List<String> {
        val stored = runCatching {
            gson.fromJson(sharedWithJson, Array<String>::class.java)?.toList()
        }.getOrNull().orEmpty()
        return (stored + userId + listOfNotNull(partnerId)).filter { it.isNotBlank() }.distinct()
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
                // SEPARATE CONCERN, deliberately not changed here: the co-parent is never
                // added, so a paired parent cannot see child info the other created. That is
                // a missing-visibility feature needing an audience policy of its own (the
                // widen-only `shareTargets` above is the shape it would take), not the
                // data-corruption bug the UseLocal branch below fixes. Widening it silently
                // would also change what the unpair sweep has to undo.
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

        // Download child info from Firestore with conflict resolution
        firestoreChildInfoDataSource.getChildInfoForParent(userId).collect { firestoreList ->
            for (firestoreData in firestoreList) {
                val remoteEntity = firestoreData.toChildInfoEntity()
                val localEntity = childInfoDao.getChildInfoById(remoteEntity.id)

                if (localEntity != null && !localEntity.syncedToFirestore) {
                    // Conflict detected - resolve it
                    val resolution = conflictResolver.resolveChildInfoConflict(
                        local = localEntity,
                        remote = remoteEntity,
                        currentUserId = userId
                    )

                    when (resolution) {
                        is ConflictResolution.UseLocal -> {
                            // Keep local, upload to remote. This is a partial `update()`, not
                            // a `set()`: `ChildInfoEntity` carries no `sharedWith`, so a full
                            // overwrite from local state dropped the field entirely. The write
                            // itself passed — the update rule reads the *old* `resource` — but
                            // the resulting document was then invisible to
                            // `getChildInfoForParent` for both parents and permanently
                            // un-updatable, because `request.auth.uid in resource.data.sharedWith`
                            // is a missing-field error from then on. The only way back was
                            // deleting the document. Same reasoning as the events branch above.
                            val localData = mapOf(
                                "id" to localEntity.id,
                                "childName" to localEntity.childName,
                                "dateOfBirth" to localEntity.dateOfBirth?.format(formatter),
                                "medications" to gson.fromJson(localEntity.medicationsJson, List::class.java),
                                "activities" to gson.fromJson(localEntity.activitiesJson, List::class.java),
                                "allergies" to gson.fromJson(localEntity.allergiesJson, List::class.java),
                                "medicalNotes" to localEntity.medicalNotes,
                                "emergencyContacts" to gson.fromJson(localEntity.emergencyContactsJson, List::class.java),
                                "schoolInfo" to localEntity.schoolInfoJson?.let { gson.fromJson(it, Map::class.java) },
                                "createdAt" to localEntity.createdAt.format(formatter),
                                "updatedAt" to LocalDateTime.now().format(formatter),
                                "createdByFirebaseUid" to localEntity.createdByFirebaseUid,
                                "lastModifiedBy" to userId
                            )
                            firestoreChildInfoDataSource.updateChildInfo(localEntity.id, localData)
                            childInfoDao.markAsSynced(localEntity.id)
                        }
                        is ConflictResolution.UseRemote -> {
                            // Use remote version
                            childInfoDao.insertChildInfo(remoteEntity.copy(syncedToFirestore = true))
                        }
                        is ConflictResolution.Merged -> {
                            // Future: handle merged data
                            childInfoDao.insertChildInfo(resolution.data.copy(syncedToFirestore = true))
                        }
                    }
                } else {
                    // No conflict - just insert/update
                    childInfoDao.insertChildInfo(remoteEntity.copy(syncedToFirestore = true))
                }
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
            recurrencePattern = (this["recurrencePattern"] as? String)?.ifBlank { null },
            recurrenceEndDate = (this["recurrenceEndDate"] as? String)?.ifBlank { null }
                ?.let { java.time.LocalDate.parse(it) },
            pickupConfirmedBy = (this["pickupConfirmedBy"] as? String)?.ifBlank { null },
            pickupConfirmedAt = (this["pickupConfirmedAt"] as? String)?.ifBlank { null }
                ?.let { LocalDateTime.parse(it, formatter) },
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            syncedToFirestore = true,
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            sharedWithJson = gson.toJson(this["sharedWith"] ?: emptyList<String>()),
            lastModifiedBy = this["lastModifiedBy"] as? String,
            permissions = this["permissions"] as? String ?: "read_write",
            imageUrl = (this["imageUrl"] as? String)?.ifBlank { null }
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

