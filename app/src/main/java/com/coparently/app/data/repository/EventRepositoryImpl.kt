package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreEventDataSource
import com.coparently.app.domain.activity.ActivityAnnouncement
import com.coparently.app.domain.activity.ActivityAnnouncer
import com.coparently.app.domain.activity.ActivityEntityType
import com.coparently.app.domain.activity.ActivityKind
import com.coparently.app.domain.events.CalendarVisibility
import com.coparently.app.domain.events.EventAcceptance
import com.coparently.app.domain.events.EventAcceptanceTransition
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
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreEventDataSource: FirestoreEventDataSource,
    private val activityAnnouncer: ActivityAnnouncer
) : EventRepository {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val dateOnlyFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val gson = Gson()

    override fun getAllEvents(): Flow<List<Event>> {
        return eventDao.getAllEvents().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * Every event in the range, recurring ones expanded into their occurrences — minus the ones
     * no calendar view may draw.
     *
     * The filter lives here because this is the query the month grid, the week view and the day
     * view all read from, so one rule covers every view rather than three that can disagree.
     * CLAUDE.md already requires range queries to go through this function; [CalendarVisibility]
     * is what it now enforces.
     *
     * Recurring masters are filtered **before** expansion. Occurrences share their master's id
     * and carry nothing of their own, so a series waiting on the co-parent produces no
     * occurrences at all rather than a set that has to be filtered again afterwards.
     */
    override fun getEventsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        // Non-recurring events matched by the query + recurring events expanded
        // into their occurrences within the range.
        return kotlinx.coroutines.flow.combine(
            eventDao.getSingleEventsByDateRange(start, end),
            eventDao.getRecurringEventsStartedBefore(end)
        ) { single, recurring ->
            val singleEvents = CalendarVisibility.visible(single.map { it.toDomain() })
            val occurrences = com.coparently.app.domain.usecase.RecurrenceExpander.expandAll(
                CalendarVisibility.visible(recurring.map { it.toDomain() }),
                start,
                end
            )
            (singleEvents + occurrences).sortedBy { it.startDateTime }
        }
    }

    /** One day's events, filtered by the same rule as the range query. */
    override fun getEventsByDate(date: LocalDateTime): Flow<List<Event>> {
        return eventDao.getEventsByDate(date).map { entities ->
            CalendarVisibility.visible(entities.map { it.toDomain() })
        }
    }

    override suspend fun getEventById(id: String): Event? {
        // `EventDao.getEventById` deliberately returns pending tombstones — the sync and delete
        // paths need to tell "deleted here" apart from "never existed". A caller asking on a
        // user's behalf wants neither: to them the event is gone.
        return eventDao.getEventById(id)?.takeIf { it.deletedAtMillis == null }?.toDomain()
    }

    override fun getEventsByParent(parentOwner: String): Flow<List<Event>> {
        return eventDao.getEventsByParent(parentOwner).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun insertEvent(event: Event) {
        val firebaseUser = firebaseAuthService.getCurrentUser()
        // `createdByFirebaseUid` records who created the event, not whether it ever synced —
        // it must be stamped here, unconditionally, rather than only inside the sync branch
        // below. A private event is never synced by design, and an offline device or a failed
        // sync leaves `syncedToFirestore` false indefinitely; either way the old code left the
        // field null forever. That field is also what scopes `ParentSlotMigrator`'s re-stamp
        // (`WHERE createdByFirebaseUid = :myUid`), so a null row could never be found once
        // pairing moved this device to a different slot — private events were the worst case,
        // since they get no second chance from a later sync to pick up the stamp.
        val stamped = if (firebaseUser != null) {
            event.copy(createdByFirebaseUid = event.createdByFirebaseUid ?: firebaseUser.uid)
        } else {
            event
        }.let { withAcceptance(it, firebaseUser?.uid) }
        eventDao.insertEvent(stamped.toEntity())

        if (firebaseUser != null && !stamped.syncedToFirestore && !stamped.isPrivate) {
            val audience = shareTargets(stamped, firebaseUser.uid, firebaseUser.uid)
            firestoreEventDataSource.insertEvent(stamped.id, stamped.toFirestoreMap(firebaseUser.uid, audience))

            val syncedEvent = stamped.copy(
                syncedToFirestore = true,
                createdByFirebaseUid = firebaseUser.uid,
                sharedWith = audience
            )
            eventDao.updateEvent(syncedEvent.toEntity())
        }

        announce(stamped, ActivityKind.EVENT_CREATED)
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
            val audience = shareTargets(event, uid, firebaseUser.uid)
            firestoreEventDataSource.updateEvent(event.id, event.toFirestoreMap(uid, audience))
            // Converge the Room copy on what was uploaded. Without this the stale audience
            // survives locally until the next down-sync, which is exactly the window the
            // unpair sweep cannot reach.
            if (event.sharedWith != audience) {
                eventDao.updateEvent(event.copy(sharedWith = audience).toEntity())
            }
        }

        announce(event, event.acceptanceKind() ?: ActivityKind.EVENT_UPDATED)
    }

    /**
     * Deletes an event, in a way the co-parent can actually find out about (CQ-3).
     *
     * The row is **tombstoned** rather than removed: every query hides it immediately, so the
     * parent sees the event go at once, but it survives in Room as an outbox entry until the
     * deletion has been written to Firestore. That is the whole difference. Before this, the row
     * was removed and the remote document deleted on a best-effort call whose failure was logged
     * and dropped — so an offline or rejected delete left the document standing and the next
     * sync pulled the event back onto the phone that had just deleted it, while a *successful*
     * delete made the document vanish with nothing left for the co-parent's downstream pass to
     * act on. One case undid the delete locally; the other never delivered it. A tombstone fixes
     * both, because it is a fact that can be retried and a fact that can be read.
     *
     * A private event is the one thing dropped outright: it is never uploaded (see
     * [updateEvent] and `SyncService`), so there is no remote copy to mark and no co-parent
     * holding one. Everything else keeps its tombstone until a remote write confirms it —
     * including while signed out, where the deletion simply waits for the next sync.
     */
    override suspend fun deleteEvent(event: Event) {
        val deletedAtMillis = System.currentTimeMillis()
        eventDao.markDeleted(event.id, deletedAtMillis)
        announce(event, ActivityKind.EVENT_DELETED)

        if (event.isPrivate) {
            eventDao.deleteEventById(event.id)
            return
        }

        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val tombstoned = firestoreEventDataSource.tombstoneEvent(
            id = event.id,
            deletedAtMillis = deletedAtMillis,
            deletedBy = firebaseUser.uid
        )
        if (tombstoned.isSuccess) {
            // Delivered. Nothing is served by keeping the row: the co-parent learns from the
            // document, and this device already hides it.
            eventDao.deleteEventById(event.id)
        } else {
            android.util.Log.w(
                "EventRepo",
                "Event tombstone not written; the deletion stays queued for the next sync",
                tombstoned.exceptionOrNull()
            )
        }
    }

    override suspend fun deleteEventById(id: String) {
        val event = eventDao.getEventById(id)
        when {
            event == null -> eventDao.deleteEventById(id)
            // Already tombstoned: the deletion is queued and dated. Running it again would
            // stamp a second, later time on the remote document than the one Room is holding,
            // which is not wrong so much as two answers to one question.
            event.deletedAtMillis != null -> Unit
            else -> deleteEvent(event.toDomain())
        }
    }

    override suspend fun pullOnce() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        // Take a single snapshot; collecting the flow here would never complete
        val entities = eventDao.getAllEvents().first()
        entities.forEach { entity ->
            if (!entity.syncedToFirestore && !entity.isPrivate) {
                val event = entity.toDomain()
                val audience = shareTargets(event, firebaseUser.uid, firebaseUser.uid)
                firestoreEventDataSource.insertEvent(event.id, event.toFirestoreMap(firebaseUser.uid, audience))

                val syncedEvent = event.copy(
                    syncedToFirestore = true,
                    createdByFirebaseUid = firebaseUser.uid,
                    sharedWith = audience
                )
                eventDao.updateEvent(syncedEvent.toEntity())
            }
        }
    }

    /**
     * Resolves the `sharedWith` audience for a remote event write.
     *
     * The audience is the *entitled* set derived from live state: the signed-in user, the
     * document's creator, and the signed-in user's current co-parent. All three matter —
     * `sharedWith` is what both the `events` read rule and
     * [FirestoreEventDataSource.observeEventsSharedWith] are keyed on, so a parent missing
     * from the list simply cannot see the event, and a co-parent editing an event must
     * never drop the creator. Only the *current* user's pairing row is consulted, never the
     * creator's, because that is the only one guaranteed to be mirrored locally.
     *
     * The event's stored list is **intersected** with that set rather than unioned into it.
     * Unioning is what made `unpairCoParent`'s server-side revocation sweep undoable: the
     * sweep narrows the remote document but never touches the local Room copy, so the very
     * next edit re-uploaded the ex-partner and handed back read and `read_write` access for
     * good. Intersecting means the audience can only ever contain people the *current*
     * pairing state entitles, so it is correct on whichever device edits next — including
     * the device that never called unpair and whose Room copy is still stale. Widening for
     * a *new* co-parent still works, because the new partner is in the entitled set.
     *
     * @param event The event about to be written.
     * @param creatorUid The document's `createdByFirebaseUid`.
     * @param currentUid The signed-in user's Firebase UID.
     */
    /**
     * Marks [event] as needing the co-parent's word, when it does.
     *
     * The creator's own slot comes from their `users` row — the **same** source
     * `AddEditEventScreen` reads to pick the event's default `parentOwner` (`User.role`, through
     * `asNamedParent`). That matters more than its accuracy: CLAUDE.md records that `User.role` is
     * a mirror the pairing accept path does not write, so it can lag a slot reassignment. Reading
     * the same lagging value the form read keeps "I made this for myself" true relative to what
     * the parent was actually shown; reading a fresher one from somewhere else would let the two
     * disagree and hide a parent's own event from their own calendar.
     *
     * Only ever *adds* the requirement, and only to an event that arrived at the default. An
     * event already carrying a decision — a re-inserted row, an undo restoring a captured event —
     * keeps it, so nothing re-asks a question that has been answered.
     */
    /**
     * Tells the co-parent about [event], unless there is a reason not to.
     *
     * Suppressed for a **private** event, which never reaches Firestore at all — announcing one
     * would leak it through a channel the sync path is careful to close, which is a worse
     * disclosure than the calendar entry itself would have been.
     *
     * Suppressed for an event this device did not create: a change arriving from sync must not be
     * echoed back to the parent who made it. The repository's write paths run for both, so the
     * check belongs here rather than in each caller.
     *
     * Never throws — see [ActivityAnnouncer]. An announcement that fails is logged and dropped,
     * because a parent's event must not fail to save because chat is down.
     */
    private suspend fun announce(event: Event, kind: ActivityKind) {
        val myUid = firebaseAuthService.getCurrentUser()?.uid ?: return
        val mine = event.lastModifiedBy?.takeIf { it.isNotBlank() }
            ?: event.createdByFirebaseUid
        activityAnnouncer.announce(
            announcement = ActivityAnnouncement(
                kind = kind,
                entityType = ActivityEntityType.EVENT,
                entityId = event.id,
                title = event.title,
                whenIso = event.startDateTime.format(dateFormatter)
            ),
            senderName = userDao.getUserById(myUid)?.name.orEmpty(),
            suppress = event.isPrivate || (mine != null && mine != myUid)
        )
    }

    /** The announcement an acceptance decision deserves, or null when this was an ordinary edit. */
    private fun Event.acceptanceKind(): ActivityKind? = when (acceptance) {
        EventAcceptance.ACCEPTED -> ActivityKind.EVENT_ACCEPTED
        EventAcceptance.DECLINED -> ActivityKind.EVENT_DECLINED
        EventAcceptance.NOT_REQUIRED, EventAcceptance.PENDING -> null
    }

    private suspend fun withAcceptance(event: Event, currentUid: String?): Event {
        if (currentUid == null || event.acceptance != EventAcceptance.NOT_REQUIRED) return event
        val me = userDao.getUserById(currentUid)
        val required = EventAcceptanceTransition.required(
            event = event,
            creatorSlot = me?.role,
            partnerUid = me?.partnerId
        )
        return if (required) event.copy(acceptance = EventAcceptance.PENDING) else event
    }

    private suspend fun shareTargets(event: Event, creatorUid: String, currentUid: String): List<String> {
        val partnerId = userDao.getUserById(currentUid)?.partnerId
        val entitled = (listOf(currentUid, creatorUid) + listOfNotNull(partnerId))
            .filter { it.isNotBlank() }
            .distinct()
        // Stored order first, so an unchanged audience keeps a stable field value.
        return (event.sharedWith.filter { it in entitled } + entitled).distinct()
    }

    /**
     * Builds the Firestore document map for this event.
     * Single source of truth for the remote schema.
     */
    private fun Event.toFirestoreMap(creatorUid: String, audience: List<String>): Map<String, Any?> {
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
            "sharedWith" to audience,
            "lastModifiedBy" to (lastModifiedBy ?: creatorUid),
            "permissions" to permissions,
            "imageUrl" to (imageUrl ?: ""),
            "acceptance" to acceptance.name,
            "acceptedBy" to (acceptedBy ?: ""),
            "acceptedAt" to (acceptedAt?.format(dateFormatter) ?: ""),
            "isImportant" to isImportant,
            "friendParticipates" to (friendParticipates ?: ""),
            // Round-tripped, not dropped: omitting it here meant the download half of a full
            // sync REPLACEd the creator's own row with a map that had no reminder, wiping the
            // value and (on the next update) cancelling the scheduled WorkManager reminder.
            "reminderMinutes" to reminderMinutes
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
            imageUrl = imageUrl,
            // An unrecognised status from a newer build reads as NOT_REQUIRED rather than
            // throwing. That is the safe direction: the worst case is an event that shows when a
            // newer build would have hidden it, against a crash on the path that draws the grid.
            acceptance = runCatching { EventAcceptance.valueOf(acceptance) }
                .getOrDefault(EventAcceptance.NOT_REQUIRED),
            acceptedBy = acceptedBy,
            acceptedAt = acceptedAt,
            isImportant = isImportant,
            friendParticipates = friendParticipates
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
            imageUrl = imageUrl,
            acceptance = acceptance.name,
            acceptedBy = acceptedBy,
            acceptedAt = acceptedAt,
            isImportant = isImportant,
            friendParticipates = friendParticipates
        )
    }
}
