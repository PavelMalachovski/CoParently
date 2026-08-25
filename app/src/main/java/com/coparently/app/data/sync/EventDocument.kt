package com.coparently.app.data.sync

import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.domain.family.FamilyMemberRef
import com.google.gson.Gson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The one reader of an `events` Firestore document.
 *
 * `EventRepositoryImpl.toFirestoreMap()` is the single definition of how an event is *written*
 * (CLAUDE.md, "Things that are easy to get wrong", item 5). This is its counterpart, extracted
 * from `SyncService` when a second caller appeared: the change-request inbox now fetches an
 * event it does not have locally, and a second copy of this mapping would be one more place for
 * the schema to drift out of step.
 */
internal object EventDocument {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val gson = Gson()

    /**
     * Reads one document into a Room row, already marked as coming from the server.
     *
     * Throws rather than guessing when a required field is missing or malformed — a caller
     * mirroring a snapshot catches per document, so one unparseable event does not take the
     * rest of the calendar with it.
     *
     * @param data The raw document.
     */
    @Suppress("UNCHECKED_CAST")
    fun toEntity(data: Map<String, Any?>): EventEntity = EventEntity(
        id = data["id"] as String,
        title = data["title"] as String,
        description = data["description"] as? String,
        startDateTime = LocalDateTime.parse(data["startDateTime"] as String, formatter),
        endDateTime = (data["endDateTime"] as? String)?.let { LocalDateTime.parse(it, formatter) },
        eventType = data["eventType"] as String,
        parentOwner = data["parentOwner"] as String,
        isRecurring = data["isRecurring"] as? Boolean ?: false,
        recurrencePattern = (data["recurrencePattern"] as? String)?.ifBlank { null },
        recurrenceEndDate = (data["recurrenceEndDate"] as? String)?.ifBlank { null }
            ?.let { LocalDate.parse(it) },
        pickupConfirmedBy = (data["pickupConfirmedBy"] as? String)?.ifBlank { null },
        pickupConfirmedAt = (data["pickupConfirmedAt"] as? String)?.ifBlank { null }
            ?.let { LocalDateTime.parse(it, formatter) },
        createdAt = LocalDateTime.parse(data["createdAt"] as String, formatter),
        updatedAt = LocalDateTime.parse(data["updatedAt"] as String, formatter),
        syncedToFirestore = true,
        createdByFirebaseUid = data["createdByFirebaseUid"] as? String,
        sharedWithJson = gson.toJson(data["sharedWith"] ?: emptyList<String>()),
        lastModifiedBy = data["lastModifiedBy"] as? String,
        permissions = data["permissions"] as? String ?: "read_write",
        imageUrl = (data["imageUrl"] as? String)?.ifBlank { null },
        // Absent reads as NOT_REQUIRED: every document written before this field existed was
        // created without an acceptance step, and defaulting the other way would hide it.
        acceptance = (data["acceptance"] as? String)?.ifBlank { null } ?: "NOT_REQUIRED",
        acceptedBy = (data["acceptedBy"] as? String)?.ifBlank { null },
        acceptedAt = (data["acceptedAt"] as? String)?.ifBlank { null }
            ?.let { LocalDateTime.parse(it, formatter) },
        // Absent reads as false: a document written before this field existed carries no such
        // expectation, and inventing one would put an exclamation mark on somebody else's
        // ordinary event.
        isImportant = data["isImportant"] as? Boolean ?: false,
        friendParticipates = (data["friendParticipates"] as? String)?.takeIf { it.isNotEmpty() },
        // Firestore returns numbers as Long; null when the document predates the field.
        reminderMinutes = (data["reminderMinutes"] as? Number)?.toInt(),
        // Absent reads as "the whole family", which is what every event written before the
        // reference type is. A reference this build does not understand is carried through
        // rather than dropped, so a co-parent on a newer build cannot have their tag erased by
        // an edit made here — see `FamilyMemberRef.Unknown`.
        forMembersJson = membersJson(data["forMembers"]),
        // Absent reads as null — "belongs to nobody but its creator" — which is what every
        // document written before the field existed is.
        familyId = (data["familyId"] as? String)?.takeIf { it.isNotEmpty() }
    )

    /**
     * The JSON a Room row stores, from a document's `forMembers` array.
     *
     * Normalised on the way in — blanks dropped, duplicates collapsed — so a row's stored form
     * does not depend on which build wrote the document.
     */
    fun membersJson(raw: Any?): String = gson.toJson(FamilyMemberRef.store(FamilyMemberRef.parse(raw)))

    /**
     * The `forMembers` array a document carries, from a Room row's JSON column.
     *
     * Here rather than in `SyncService` because this file is the one place the events wire
     * format is defined; a second copy of the conversion is one more place for it to drift.
     */
    fun storedMembers(forMembersJson: String): List<String> {
        val stored = runCatching {
            gson.fromJson(forMembersJson, Array<String>::class.java)?.toList()
        }.getOrNull()
        return FamilyMemberRef.store(FamilyMemberRef.parse(stored))
    }
}
