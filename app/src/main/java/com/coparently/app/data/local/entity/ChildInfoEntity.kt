package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing child information in the local Room database.
 *
 * @property id Unique identifier for the child info
 * @property childName Name of the child
 * @property dateOfBirth Child's date of birth
 * @property medicationsJson JSON string of medications list
 * @property activitiesJson JSON string of activities list
 * @property allergiesJson JSON string of allergies list
 * @property medicalNotes Additional medical notes
 * @property emergencyContactsJson JSON string of emergency contacts list
 * @property schoolInfoJson JSON string of school information
 * @property medicalProfileJson JSON object of [com.coparently.app.domain.model.MedicalProfile];
 * `{}` when never filled
 * @property medicalPhotosJson JSON array of photograph download URLs; `[]` when none
 * @property guestsJson JSON object of guest grants keyed by uid; `{}` when none
 * @property createdAt Timestamp when the info was created
 * @property updatedAt Timestamp when the info was last updated
 * @property createdByFirebaseUid Firebase UID of the user who created this info
 * @property lastModifiedBy Firebase UID of the user who last modified this info
 * @property syncedToFirestore Whether the info has been synced to Firestore
 */
@Entity(tableName = "child_info")
data class ChildInfoEntity(
    @PrimaryKey
    val id: String,
    val childName: String,
    val dateOfBirth: LocalDateTime?,
    val medicationsJson: String, // JSON array
    val activitiesJson: String, // JSON array
    val allergiesJson: String, // JSON array
    val medicalNotes: String?,
    val emergencyContactsJson: String, // JSON array
    val schoolInfoJson: String?, // JSON object or null
    /** JSON object of [com.coparently.app.domain.model.MedicalProfile]; `{}` when never filled. */
    val medicalProfileJson: String = "{}",
    /**
     * JSON array of photograph download URLs; `[]` when none. Stored as JSON like every other
     * list on this record rather than as a relation — the app never queries by photograph.
     */
    val medicalPhotosJson: String = "[]",
    /**
     * JSON object of [com.coparently.app.domain.guests.GuestGrant] keyed by uid; `{}` when none.
     *
     * Room's copy is for display while offline. The document's copy is the one that decides
     * access, because `firestore.rules` reads that one — so a stale row here shows a parent a
     * guest who can no longer read, never a guest who can.
     */
    val guestsJson: String = "{}",
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdByFirebaseUid: String?,
    val lastModifiedBy: String?,
    val syncedToFirestore: Boolean
)

