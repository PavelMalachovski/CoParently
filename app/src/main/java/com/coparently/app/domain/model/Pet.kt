package com.coparently.app.domain.model

import java.time.LocalDateTime

/**
 * Domain model representing a family pet shared between the two parents.
 *
 * Mirrors [ChildInfo] deliberately: a pet moves between the two homes on the same custody
 * rhythm as the child, and the questions a sitter or a vet asks are the same shape — what
 * does it take, what must it not eat, who do I call. [Medication] and [Vaccination] are the
 * same value types the child's record uses, so one editor serves both and the two never
 * drift into different ideas of what a dose looks like.
 *
 * @property id Unique identifier for the pet
 * @property name The pet's name
 * @property species What kind of animal this is
 * @property breed Breed, or null when unknown or not applicable
 * @property dateOfBirth The pet's date of birth, or null when unknown
 * @property medications Medications the pet takes
 * @property vaccinations Vaccines given, with dates when the parent recorded them
 * @property specialNeeds Free-text traits both homes must know: behaviour, fears, allergies
 * @property feedingNotes What and when to feed, walking routine — what a handover needs
 * @property vetName Name of the veterinary clinic or vet, or null
 * @property vetPhone Phone number of the vet, or null
 * @property photos Download URLs of the pet's photographs, in the order they were added.
 *   Shared with the co-parent like the rest of this record.
 * @property createdAt Timestamp when the record was created
 * @property updatedAt Timestamp when the record was last updated
 * @property createdByFirebaseUid Firebase UID of the user who created this record
 * @property lastModifiedBy Firebase UID of the user who last modified this record
 * @property syncedToFirestore Whether the record has been synced to Firestore
 */
data class Pet(
    val id: String,
    val name: String,
    val species: PetSpecies = PetSpecies.OTHER,
    val breed: String? = null,
    val dateOfBirth: LocalDateTime? = null,
    val medications: List<Medication> = emptyList(),
    val vaccinations: List<Vaccination> = emptyList(),
    val specialNeeds: String? = null,
    val feedingNotes: String? = null,
    val vetName: String? = null,
    val vetPhone: String? = null,
    val photos: List<String> = emptyList(),
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdByFirebaseUid: String? = null,
    val lastModifiedBy: String? = null,
    val syncedToFirestore: Boolean = false,
    /**
     * The co-parenting relationship this record belongs to, or null while it belongs to nobody
     * but its creator. See [com.coparently.app.domain.model.Event.familyId].
     */
    val familyId: String? = null
)

/**
 * What kind of animal a [Pet] is.
 *
 * An enum rather than free text so the stored value survives a language change — the same
 * reasoning as [BloodType]. Rendering goes through `PetSpecies.labelRes()` in the
 * presentation layer, never through the constant name.
 */
enum class PetSpecies {
    DOG,
    CAT,
    BIRD,
    RODENT,
    FISH,
    REPTILE,
    OTHER;

    companion object {
        /**
         * Parses a stored constant name, falling back to [OTHER] for anything unknown.
         *
         * A record written by a newer build that knows more species must stay readable
         * here, the same forward-compatibility rule the chat timestamp read path follows.
         */
        fun fromStored(value: String?): PetSpecies =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}
