package com.coparently.app.data.sync

import android.util.Log
import com.coparently.app.data.family.SelectedFamilySource
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
import com.coparently.app.data.repository.FamilySettingsRepository
import com.coparently.app.data.repository.LocalDateJsonAdapter
import com.coparently.app.data.repository.ParentSlotMigrator
import com.coparently.app.data.repository.ParentingPlanRepository
import com.coparently.app.data.session.AccountSwitchGuard
import com.coparently.app.domain.guests.GuestGrantPolicy
import com.coparently.app.domain.repository.ChangeRequestRepository
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PetRepository
import com.google.firebase.Timestamp
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
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
    private val encryptedPreferences: EncryptedPreferences,
    private val petRepository: PetRepository,
    private val messageRepository: MessageRepository,
    private val changeRequestRepository: ChangeRequestRepository,
    private val parentingPlanRepository: ParentingPlanRepository,
    private val familySettingsRepository: FamilySettingsRepository,
    private val familyIdBackfill: FamilyIdBackfill,
    private val selectedFamilySource: SelectedFamilySource,
    private val accountSwitchGuard: AccountSwitchGuard
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

            // The upload choke point: a periodic SyncWorker run can fire before the session
            // boundary has processed an account switch, and syncing another account's
            // still-unsynced rows under this uid is the leak the guard exists to stop.
            accountSwitchGuard.ensureAccountConsistency()

            _syncStatus.value = SyncStatus.Syncing(0, 100)

            // Step 1: Sync user data (including FCM token)
            _syncStatus.value = SyncStatus.Syncing(10, 100)
            syncUserData(currentUser.uid)

            // Step 2: Name the family on rows written before there was one to name. After
            // `syncUserData`, which is what brings a freshly accepted `partnerId` down, and
            // before every upload below, so a row stamped here goes up carrying its family
            // rather than waiting for the sync after next.
            familyIdBackfill.run(
                userId = currentUser.uid,
                partnerId = userDao.getUserById(currentUser.uid)?.partnerId?.takeIf {
                    it.isNotBlank()
                }
            )

            // Step 3: Sync events
            _syncStatus.value = SyncStatus.Syncing(40, 100)
            syncEvents(currentUser.uid)

            // Step 4: Sync child info
            _syncStatus.value = SyncStatus.Syncing(70, 100)
            syncChildInfo(currentUser.uid)

            // Step 5: Sync pets. The repository handles upload, download and audience repair
            // itself (mirroring child info), so there is nothing to duplicate here.
            _syncStatus.value = SyncStatus.Syncing(80, 100)
            petRepository.pullOnce()

            // Step 6: Drain the outboxes that a live listener cannot drain for you. Chat and
            // change requests are mirrored *down* in realtime, but a write of either that was
            // refused or interrupted had nothing retrying it — a chat message stayed ERROR for
            // good, and a change request created offline never left the sender's phone.
            _syncStatus.value = SyncStatus.Syncing(90, 100)
            messageRepository.flushOutbox()
            changeRequestRepository.flushOutbox()
            // Same reason again, for the parenting plan (MON-5): its screen uploads on save,
            // and a save made offline or refused has nothing else retrying it. A parenting plan
            // is filled in over weeks, so "it will go up next time you open the screen" is not
            // good enough — the co-parent is waiting to answer beside it.
            parentingPlanRepository.flushOutbox(currentUser.uid)
            // The same shape, for the one number the wizard collects before there is anybody to
            // agree it with: an unpaired account can only cache the split ratio, and until this
            // ran nothing ever carried it across. A parent who set 70/30 during onboarding paired
            // and both phones went on dividing evenly, while Settings showed them 70/30. Writes
            // only when the pair has no agreement yet, so a tick can never overwrite one.
            familySettingsRepository.publishCachedRatioIfMissing()

            // Step 7: Complete
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
        val unsynced = eventDao.getUnsyncedEvents().filterNot { it.isPrivate }
        val (pendingDeletions, unsyncedEvents) = unsynced.partition { it.deletedAtMillis != null }

        // Deletions first. They are the half of this queue that used to have no path at all:
        // a delete was a fire-and-forget document removal whose failure was logged and dropped,
        // so an offline or rejected delete stayed undone forever and the download half below
        // put the event back. A pending tombstone is retried here on every sync until the write
        // lands, and only then does the row go for real.
        for (entity in pendingDeletions) {
            val deletedAtMillis = entity.deletedAtMillis ?: continue
            val tombstoned = firestoreEventDataSource.tombstoneEvent(
                id = entity.id,
                deletedAtMillis = deletedAtMillis,
                deletedBy = userId
            )
            if (tombstoned.isSuccess) {
                eventDao.deleteEventById(entity.id)
            } else {
                Log.w(
                    TAG,
                    "Event tombstone for ${entity.id} not written; it stays queued",
                    tombstoned.exceptionOrNull()
                )
            }
        }

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
                "imageUrl" to entity.imageUrl,
                "acceptance" to entity.acceptance,
                "acceptedBy" to entity.acceptedBy,
                "acceptedAt" to entity.acceptedAt?.format(formatter),
                "isImportant" to entity.isImportant,
                "friendParticipates" to (entity.friendParticipates ?: ""),
                // Through `EventDocument` rather than converted here: that file is the one
                // definition of the events wire format, and a second copy of this conversion is
                // one more place for the schema to drift (CLAUDE.md item 5).
                "forMembers" to EventDocument.storedMembers(entity.forMembersJson),
                "familyId" to (entity.familyId ?: "")
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

        // Download the events shared with this user (own + co-parent's). Unlike the previous
        // partner-gated block this runs whether or not the account is paired: the query is
        // authorized by `sharedWith` alone, so there is nothing to gate on.
        //
        // **Only what changed since last time (CQ-5)**, except on the periodic sweep — this used
        // to read the entire collection on every one of the 96 syncs a day, on both phones. See
        // `EventSyncWindow` for why the sweep is what makes the delta safe rather than a
        // precaution, and `FirestoreEventDataSource.observeEventsSharedWith` for why the bound is
        // a change cursor and not a date window.
        val cursorKey = PreferenceKeys.EVENT_SYNC_CURSOR_PREFIX + userId
        val sweepKey = PreferenceKeys.EVENT_SYNC_SWEEP_PREFIX + userId
        val storedCursor = encryptedPreferences.getString(cursorKey)?.toLongOrNull()
        val lastSweep = encryptedPreferences.getString(sweepKey)?.toLongOrNull()
        val startedAt = System.currentTimeMillis()
        val sweeping = EventSyncWindow.needsFullSweep(storedCursor, lastSweep, startedAt)
        val changedAfter = if (sweeping) null else storedCursor?.let { Timestamp(Date(it)) }

        firestoreEventDataSource.observeEventsSharedWith(userId, changedAfter).collect { download ->
            for (firestoreData in download.documents) {
                val remoteId = firestoreData["id"] as? String ?: continue

                // A tombstone is the co-parent telling this device the event is gone. It is
                // answered from the raw document, before it is mapped to an entity: a deletion
                // is the one thing that must not depend on the rest of the document still
                // parsing. And it is answered regardless of timestamps — `updatedAt` is a naive
                // `LocalDateTime` whose cross-time-zone ordering is known wrong (SEC-4), and a
                // rule that cannot be wrong beats one that is usually right when the question is
                // whether a cancelled event stays on a parent's calendar.
                //
                // It therefore also wins over a *concurrent edit* on this device, deliberately:
                // an event that should not exist is at least visible and can be deleted again,
                // whereas an edit that loses is simply gone.
                if (Tombstone.isDeleted(firestoreData)) {
                    eventDao.deleteEventById(remoteId)
                    continue
                }

                val localEntity = eventDao.getEventById(remoteId)

                // The mirror image: this device has deleted the event and the deletion has not
                // been written yet, so the document is still alive remotely. Inserting it would
                // undo the parent's own delete a few lines after the upload half tried to
                // deliver it.
                if (localEntity?.deletedAtMillis != null) {
                    continue
                }

                val remoteEntity = firestoreData.toEventEntity()

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
                                "imageUrl" to localEntity.imageUrl,
                                "acceptance" to localEntity.acceptance,
                                "acceptedBy" to localEntity.acceptedBy,
                                "acceptedAt" to localEntity.acceptedAt?.format(formatter),
                                "isImportant" to localEntity.isImportant,
                                "friendParticipates" to (localEntity.friendParticipates ?: ""),
                                "reminderMinutes" to localEntity.reminderMinutes,
                                "forMembers" to
                                    EventDocument.storedMembers(localEntity.forMembersJson),
                                "familyId" to (localEntity.familyId ?: "")
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

            // Advance the cursor only to what this pass actually saw, and only after the loop
            // has applied it. Writing it before, or writing `startedAt` instead, would step past
            // a document written while the query was in flight and skip it for good — the cursor
            // can only ever be a high-water mark of rows this device has taken in.
            //
            // A pass that returned nothing leaves the cursor where it was; there is no new
            // high-water mark to record, and `startedAt` is not one.
            download.highestCursor?.let { seen ->
                encryptedPreferences.putString(cursorKey, seen.toDate().time.toString())
            }
            if (sweeping) {
                encryptedPreferences.putString(sweepKey, startedAt.toString())
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

        // Upload unsynced local child info. Deletions first, and they are *not* uploadable as
        // documents: `getUnsyncedChildInfo` is the outbox and carries pending tombstones, so
        // sending one through `upsertChildInfo` — a `set()` — would rewrite the document from a
        // row that only still exists to record its own deletion, wiping the tombstone and
        // resurrecting the child on both phones.
        val (pendingDeletions, unsyncedChildInfo) =
            childInfoDao.getUnsyncedChildInfo().partition { it.deletedAtMillis != null }

        for (entity in pendingDeletions) {
            val deletedAtMillis = entity.deletedAtMillis ?: continue
            val tombstoned = firestoreChildInfoDataSource.tombstoneChildInfo(
                id = entity.id,
                deletedAtMillis = deletedAtMillis,
                deletedBy = userId
            )
            if (tombstoned.isSuccess) {
                childInfoDao.deleteChildInfoById(entity.id)
            } else {
                Log.w(
                    TAG,
                    "Child tombstone for ${entity.id} not written; it stays queued",
                    tombstoned.exceptionOrNull()
                )
            }
        }

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
                "medicalPhotos" to ChildInfoPhotos.decode(
                    gson.fromJson(entity.medicalPhotosJson, List::class.java)
                ),
                "guests" to ChildInfoGuests.encode(guestsOf(entity)),
                "createdAt" to entity.createdAt.format(formatter),
                "updatedAt" to entity.updatedAt.format(formatter),
                "createdByFirebaseUid" to entity.createdByFirebaseUid,
                "lastModifiedBy" to entity.lastModifiedBy,
                "familyId" to (entity.familyId ?: ""),
                "sharedWith" to ChildInfoAudience.entitled(
                    userId = userId,
                    creatorUid = entity.createdByFirebaseUid,
                    partnerId = partnerId,
                    guestUids = GuestGrantPolicy
                        .active(guestsOf(entity).values.toList(), Instant.now())
                        .map { it.uid }
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
                // The co-parent deleted the child record. Answered from the raw document, and
                // ahead of the conflict resolver: a deletion is decided by rule rather than by
                // comparing `updatedAt`, which is a naive `LocalDateTime` with SEC-4's ordering
                // defect.
                if (Tombstone.isDeleted(firestoreData)) {
                    childInfoDao.deleteChildInfoById(firestoreData["id"] as? String ?: continue)
                    continue
                }

                val remoteEntity = firestoreData.toChildInfoEntity()
                val localEntity = childInfoDao.getChildInfoById(remoteEntity.id)

                // This device deleted the record and the deletion has not been written yet, so
                // the document is still alive remotely. Inserting it would undo the parent's own
                // delete a few lines after the upload half tried to deliver it.
                if (localEntity?.deletedAtMillis != null) {
                    continue
                }

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
                                "medicalPhotos" to ChildInfoPhotos.decode(
                                    gson.fromJson(localEntity.medicalPhotosJson, List::class.java)
                                ),
                                "guests" to ChildInfoGuests.encode(guestsOf(localEntity)),
                                "createdAt" to localEntity.createdAt.format(formatter),
                                "updatedAt" to LocalDateTime.now().format(formatter),
                                "createdByFirebaseUid" to localEntity.createdByFirebaseUid,
                                "lastModifiedBy" to userId,
                                "familyId" to (localEntity.familyId ?: "")
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
     * `UserRepositoryImpl.pullOnce()` would also refresh it, but nothing calls that
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
                // **`partnerId` is deliberately not refreshed from the remote document.** It
                // stopped meaning "my co-parent" and started meaning "the family this device is
                // showing" (`SelectedFamilySource`), so copying the server's value here would
                // yank a parent out of the family they are looking at on every sync tick. The
                // real set arrives as `partnerIds`, below, and the selection is reconciled
                // against it afterwards.
                partnerIdsJson = gson.toJson(remotePartnerUids(remoteUserData)),
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

            // Now that the set is current, make the projection agree with it. This is what
            // moves a device off a family the parent has been removed from — an unpair
            // performed on the other phone reaches this one only as a shorter `partnerIds`.
            selectedFamilySource.reconcile()

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

        // Null for an action nothing announces. Skipping is the whole of the handling: the
        // receiving device composes the sentence from the type now, so a payload it has no
        // type for is a push that would be dropped on arrival.
        val notificationPayload = fcmService.createEventNotificationPayload(
            eventId = eventId,
            eventTitle = eventTitle,
            action = action,
            performedBy = userData.name
        ) ?: return

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
     *
     * Delegates to [EventDocument], which is the single reader of the document shape now that
     * the change-request inbox fetches events too.
     */
    private fun Map<String, Any?>.toEventEntity() = EventDocument.toEntity(this)

    /**
     * The guest grants stored on [entity], decoded through the one reader.
     *
     * Round-tripping Room's JSON through [ChildInfoGuests] rather than shipping the raw string
     * is what keeps this map and the repository's from disagreeing about a half-written grant:
     * both drop the same ones, on the same rule.
     */
    private fun guestsOf(entity: com.coparently.app.data.local.entity.ChildInfoEntity) =
        ChildInfoGuests.decode(gson.fromJson(entity.guestsJson, Map::class.java))

    /**
     * Converts Firestore child info data to ChildInfoEntity.
     */
    @Suppress("UNCHECKED_CAST")
    /**
     * The co-parents a remote `users/{uid}` document names, in either shape it may carry.
     *
     * `partnerIds` is the answer and the singular `partnerId` is the fallback, unioned rather
     * than one winning — the same rule `UserRepositoryImpl.partnerUids` and `functions`'
     * `partnersOf` follow, because three places deciding who somebody co-parents with must
     * decide it identically.
     */
    private fun remotePartnerUids(remote: Map<String, Any?>): List<String> {
        val many = (remote["partnerIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val one = listOfNotNull(remote["partnerId"] as? String)
        return (many + one).filter { it.isNotBlank() }.distinct()
    }

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
            medicalPhotosJson = gson.toJson(ChildInfoPhotos.decode(this["medicalPhotos"])),
            guestsJson = gson.toJson(ChildInfoGuests.encode(ChildInfoGuests.decode(this["guests"]))),
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            lastModifiedBy = this["lastModifiedBy"] as? String,
            syncedToFirestore = true,
            familyId = (this["familyId"] as? String)?.takeIf { it.isNotEmpty() }
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

