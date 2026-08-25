package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing a pet in the local Room database.
 *
 * List-shaped fields are JSON string columns, the same shape [ChildInfoEntity] uses —
 * the app never queries by medication or photograph, so relations would buy nothing.
 *
 * @property id Unique identifier for the pet
 * @property name The pet's name
 * @property species Stored [com.coparently.app.domain.model.PetSpecies] constant name
 * @property breed Breed, or null when unknown
 * @property dateOfBirth The pet's date of birth
 * @property medicationsJson JSON array of medications
 * @property vaccinationsJson JSON array of vaccinations
 * @property specialNeeds Free-text traits both homes must know
 * @property feedingNotes What and when to feed, walking routine
 * @property vetName Name of the veterinary clinic or vet
 * @property vetPhone Phone number of the vet
 * @property photosJson JSON array of photograph download URLs; `[]` when none
 * @property createdAt Timestamp when the record was created
 * @property updatedAt Timestamp when the record was last updated
 * @property createdByFirebaseUid Firebase UID of the user who created this record
 * @property lastModifiedBy Firebase UID of the user who last modified this record
 * @property syncedToFirestore Whether the record has been synced to Firestore
 */
@Entity(tableName = "pets")
data class PetEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val species: String,
    val breed: String?,
    val dateOfBirth: LocalDateTime?,
    val medicationsJson: String, // JSON array
    val vaccinationsJson: String, // JSON array
    val specialNeeds: String?,
    val feedingNotes: String?,
    val vetName: String?,
    val vetPhone: String?,
    val photosJson: String = "[]", // JSON array
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdByFirebaseUid: String?,
    val lastModifiedBy: String?,
    val syncedToFirestore: Boolean,
    /**
     * The co-parenting relationship this record belongs to, or null while it belongs to nobody
     * but its creator. See [com.coparently.app.data.local.entity.EventEntity.familyId].
     */
    val familyId: String? = null
)
