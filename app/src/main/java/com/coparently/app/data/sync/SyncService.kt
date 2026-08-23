package com.coparently.app.data.sync

import android.util.Log
import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.coparently.app.data.repository.LocalDateJsonAdapter
import com.coparently.app.data.repository.ParentSlotMigrator
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
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
// Ten collaborators, all injected: a Hilt graph edge list, not a call signature anybody
// writes by hand, and grouping them behind a wrapper type would only hide which sync
// destinations this service actually has - the same reasoning `PairingViewModel` uses.
@Suppress("LongParameterList")
class SyncService @Inject constructor(
    private val eventDao: EventDao,
    private val childInfoDao: ChildInfoDao,
    private val userDao: UserDao,
    private val firestoreEventDataSource: FirestoreEventDataSource,
    private val firestoreChildInfoDataSource: FirestoreChildInfoDataSource,
    private val firestoreUserDataSource: FirestoreUserDataSource,
    private val firebaseAuthService: FirebaseAuthService,
    private val fcmService: FcmService,
    private val conflictResolver: ConflictResolver,
    private val parentSlotMigrator: ParentSlotMigrator,
    private val encryptedPreferences: EncryptedPreferences
) {
    // `LocalDate::class.java` needs the same adapter `ChildInfoRepositoryImpl` and
    // `UserRepositoryImpl` register: `Vaccination.date` is a `LocalDate`, and a document read
    // back through a Gson without this adapter would fail to parse it back out of the ISO
    // string the repositories already write - see `medicalProfile` handling below.
    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()
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
        val partnerId = userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }
        // Before the read below, not after: the backfill's whole effect is to clear the flag
        // `getUnsyncedEvents` selects on, so a read taken first would not see it.
        backfillAudienceForPartner(userId, partnerId)

        // Upload unsynced local events; private events never leave the device
        val unsyncedEvents = eventDao.getUnsyncedEvents().filterNot { it.isPrivate }

        for (entity in unsyncedEvents) {
            val audience = shareTargets(
                sharedWithJson = entity.sharedWithJson,
                creatorUid = entity.createdByFirebaseUid,
                userId = userId,
                partnerId = partnerId
            )
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
                "sharedWith" to audience,
                "lastModifiedBy" to entity.lastModifiedBy,
                "permissions" to entity.permissions,
                "imageUrl" to entity.imageUrl
            )

            val result = firestoreEventDataSource.insertEvent(entity.id, eventData)
            if (result.isSuccess) {
                eventDao.markAsSynced(entity.id)

                // Notify everyone the event is shared with, except the uploader
                for (recipientId in audience) {
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
     * Re-publishes this user's own events once per co-parent, so a pair formed after those
     * events were created can actually read them.
     *
     * `sharedWith` is computed at upload time and never recomputed for a row already marked
     * synced, so every event created while unpaired kept an audience of one. The accepter's
     * rows were re-flagged as a side effect of the parent-slot re-stamp
     * ([EventDao.reslotOwner]); the inviter's never were, because the inviter keeps their slot.
     *
     * Keyed on the partner's uid rather than a boolean, so re-pairing with somebody else
     * re-arms it: the new co-parent has received nothing.
     *
     * The marker advances after the Room `UPDATE` commits and before the uploads it queues have
     * finished. A process death in that window is harmless — the rows stay flagged and the next
     * pass uploads them, because the marker guards the flagging and not the upload. Advancing it
     * only after the uploads would instead re-flag every event on every sync.
     */
    private suspend fun backfillAudienceForPartner(userId: String, partnerId: String?) {
        val key = "${PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX}$userId"

        // Unpaired: disarm the marker rather than simply doing nothing.
        //
        // `unpairCoParent`'s server-side sweep narrows every shared document's `sharedWith`,
        // so the ex-partner loses access to everything. If the marker were left naming them,
        // re-pairing with the *same* co-parent would find it already equal to their uid and
        // skip the backfill — and the accepter's re-stamp would not cover it either, because
        // the slots come out the same way round the second time and `ParentSlotMigrator.reslot`
        // returns 0 on `from == to`. The pair would look correctly paired while everything
        // created before the unpair stayed unreadable to one of them, with nothing said.
        //
        // Blank rather than a removed key: `EncryptedPreferences` has no generic remove, and
        // blank can never equal a real uid, so it re-arms exactly the same way an absent
        // marker does.
        if (partnerId == null) {
            if (!encryptedPreferences.getString(key).isNullOrBlank()) {
                encryptedPreferences.putString(key, "")
            }
            return
        }
        if (encryptedPreferences.getString(key) == partnerId) return

        val requeued = eventDao.markOwnEventsUnsynced(userId)
        encryptedPreferences.putString(key, partnerId)
        Log.i(
            TAG,
            "Audience backfill for $userId with partner $partnerId: re-queued $requeued event(s)"
        )
    }

    /**
     * Re-publishes this user's own child info once per co-parent, so rows written before pairing
     * become readable by that co-parent.
     *
     * Without this, item 5 fails silently in the one case that matters most: a parent fills in
     * everything about their child, *then* invites the other parent, and the other parent sees an
     * empty screen with no error.
     *
     * Two rules are copied deliberately from [backfillAudienceForPartner] rather than simplified:
     *
     * - The marker stores the **partner's UID**, not a flag. A flag never re-arms when the same
     *   two people pair again after an unpair, and the pair then looks correctly linked while
     *   everything from before stays invisible to one of them.
     * - When unpaired the marker is **blanked**, not left alone. Leaving it naming an ex-partner
     *   means re-pairing with that same person finds it already equal and skips the backfill.
     *   `EncryptedPreferences` has no generic remove, and a blank value can never equal a real
     *   UID, so it re-arms exactly as an absent marker does.
     */
    private suspend fun backfillChildInfoAudienceForPartner(userId: String, partnerId: String?) {
        val key = "${PreferenceKeys.CHILD_INFO_AUDIENCE_BACKFILL_PREFIX}$userId"

        if (partnerId == null) {
            if (!encryptedPreferences.getString(key).isNullOrBlank()) {
                encryptedPreferences.putString(key, "")
            }
            return
        }
        if (encryptedPreferences.getString(key) == partnerId) return

        val requeued = childInfoDao.markOwnChildInfoUnsynced(userId)
        encryptedPreferences.putString(key, partnerId)
        Log.i(
            TAG,
            "Child-info audience backfill for $userId with partner $partnerId: " +
                "re-queued $requeued row(s)"
        )
    }

    /**
     * Resolves the `sharedWith` audience for an event upload.
     *
     * The audience is the entitled set derived from live state — the uploader, the
     * document's creator and the uploader's current co-parent — and the stored
     * [sharedWithJson] is **intersected** with it, never unioned into it. All three
     * entitled UIDs must be present because the `events` read rule and
     * [FirestoreEventDataSource.observeEventsSharedWith] are both keyed on this list: an
     * event missing from it is invisible to the parent it belongs to, and the whole
     * down-sync is only meaningful once co-parent events actually carry the reader's UID.
     *
     * Intersecting is what makes `unpairCoParent`'s revocation survive this code path. The
     * server sweep narrows the remote document but never the local Room copy, and this
     * upload runs *before* the down-sync that would heal it — so under the previous
     * widen-only rule every event still sitting `syncedToFirestore = false` at unpair time
     * re-granted the ex-partner access on the very next sync. See
     * `EventRepositoryImpl.shareTargets` for the same reasoning on the edit path.
     *
     * @param sharedWithJson The entity's stored JSON array of Firebase UIDs.
     * @param creatorUid The entity's `createdByFirebaseUid`, or null if it never synced.
     * @param userId The uploading user's Firebase UID.
     * @param partnerId The co-parent's Firebase UID, or null when unpaired.
     */
    private fun shareTargets(
        sharedWithJson: String,
        creatorUid: String?,
        userId: String,
        partnerId: String?
    ): List<String> {
        val stored = runCatching {
            gson.fromJson(sharedWithJson, Array<String>::class.java)?.toList()
        }.getOrNull().orEmpty()
        val entitled = (listOf(userId) + listOfNotNull(creatorUid, partnerId))
            .filter { it.isNotBlank() }
            .distinct()
        // Stored order first, so an unchanged audience keeps a stable field value.
        return (stored.filter { it in entitled } + entitled).distinct()
    }

    /**
     * Syncs child information between local database and Firestore.
     */
    private suspend fun syncChildInfo(userId: String) {
        val partnerId = userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }
        backfillChildInfoAudienceForPartner(userId, partnerId)

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
                "medicalProfile" to gson.fromJson(entity.medicalProfileJson, Map::class.java),
                "createdAt" to entity.createdAt.format(formatter),
                "updatedAt" to entity.updatedAt.format(formatter),
                "createdByFirebaseUid" to entity.createdByFirebaseUid,
                "lastModifiedBy" to entity.lastModifiedBy,
                "sharedWith" to ChildInfoAudience.entitled(
                    userId = userId,
                    creatorUid = entity.createdByFirebaseUid,
                    partnerId = partnerId
                )
            )

            val result = firestoreChildInfoDataSource.upsertChildInfo(entity.id, childInfoData)
            if (result.isSuccess) {
                childInfoDao.markAsSynced(entity.id)

                // Notify partner
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
                                "medicalProfile" to gson.fromJson(localEntity.medicalProfileJson, Map::class.java),
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
     * Syncs user data including FCM token, `partnerId` and `role`.
     *
     * `role` is refreshed here, alongside the fields this already downloaded, because this is
     * the only path in the app that periodically re-reads the signed-in user's own document —
     * `UserRepositoryImpl.syncWithFirestore()` would also refresh it, but nothing calls that
     * method. Before this, a slot flipped server-side (a `backfillParentSlots` run for a pair
     * that accepted long ago — see `functions/index.js`) was never noticed by a running app:
     * this device would keep stamping new records with the slot it already had, while Firestore
     * held the new one, and every record it had ever created would start reading as its
     * co-parent's the moment anyone compared the two.
     *
     * [updatedUser]'s role is handed to [ParentSlotMigrator.reslotIfSlotChanged] as the incoming
     * value; the *previous* value it compares against is not read from Room at all — see that
     * method's KDoc for why `role` cannot be the "before" side of this comparison (it is never
     * written by the accept path, it is seeded with a placeholder on profile creation, and it is
     * written non-atomically with respect to the re-stamp).
     *
     * A failure reacting to the change is logged and swallowed rather than left to fail
     * [performFullSync]: the token, `partnerId` and `role` fields already written above are
     * real progress, and a re-stamp failure must not undo it by aborting the events and
     * child-info steps that run after this one for the same sync pass.
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
                fcmToken = remoteUserData["fcmToken"] as? String,
                // A document that predates the `role` field, a failed partial read, or one
                // that (should not, but did) carry a blank string must not blank out a role
                // Room already has - `role` is non-nullable, unlike partnerId/fcmToken above,
                // and there is no "unknown slot" to fall back to. Blank is rejected the same
                // way `ParentSlotMigrator.reslotIfSlotChanged` rejects a blank incoming role,
                // so a document with `role: ""` cannot get stamped onto new records and then
                // permanently block a real re-stamp of them.
                role = (remoteUserData["role"] as? String)?.takeIf { it.isNotBlank() } ?: localUser.role
            )
            userDao.updateUser(updatedUser)

            // The parent slot as the server actually states it, next to what this device held.
            // Without this the two are indistinguishable in the field: a slot that never got
            // assigned, one the client overwrote, and one this sync simply has not reached yet
            // all present identically as "Room says mom". `ParentSlotMigrator` logs its
            // re-stamp count for the same reason — a silent value nobody can read is how the
            // both-parents-in-slot-1 defect stayed invisible through three pairing rounds.
            Log.i(
                TAG,
                "Profile sync for $userId: remote role=${remoteUserData["role"]}, " +
                    "local=${localUser.role}, applied=${updatedUser.role}"
            )

            runCatching {
                parentSlotMigrator.reslotIfSlotChanged(myUid = userId, newRole = updatedUser.role)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to react to a remote slot change for $userId", e)
            }
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
            medicalProfileJson = gson.toJson(this["medicalProfile"] ?: emptyMap<String, Any?>()),
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            lastModifiedBy = this["lastModifiedBy"] as? String,
            syncedToFirestore = true
        )
    }

    private companion object {
        const val TAG = "SyncService"
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

