package com.coparently.app.domain.model

import java.time.LocalDateTime

/**
 * Domain model representing information about a child.
 * Stores important shared data like medications, activities, allergies, etc.
 *
 * @property id Unique identifier for the child info
 * @property childName Name of the child
 * @property dateOfBirth Child's date of birth
 * @property medications List of medications the child takes
 * @property activities List of child's activities (sports, clubs, etc.)
 * @property allergies List of known allergies
 * @property medicalNotes Additional medical notes
 * @property emergencyContacts List of emergency contact information
 * @property schoolInfo Information about the child's school
 * @property createdAt Timestamp when the info was created
 * @property updatedAt Timestamp when the info was last updated
 * @property createdByFirebaseUid Firebase UID of the user who created this info
 * @property lastModifiedBy Firebase UID of the user who last modified this info
 * @property syncedToFirestore Whether the info has been synced to Firestore
 */
data class ChildInfo(
    val id: String,
    val childName: String,
    val dateOfBirth: LocalDateTime?,
    val medications: List<Medication> = emptyList(),
    val activities: List<Activity> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medicalNotes: String? = null,
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val schoolInfo: SchoolInfo? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdByFirebaseUid: String? = null,
    val lastModifiedBy: String? = null,
    val syncedToFirestore: Boolean = false
)

/**
 * Information about a medication.
 *
 * @property name Name of the medication
 * @property dosage Dosage information
 * @property frequency How often to take (e.g., "2 times daily")
 * @property notes Additional notes about the medication
 */
data class Medication(
    val name: String,
    val dosage: String,
    val frequency: String,
    val notes: String? = null
)

/**
 * Information about a child's activity.
 *
 * @property name Name of the activity (e.g., "Soccer", "Piano lessons")
 * @property schedule When the activity takes place (e.g., "Monday and Wednesday, 4-5 PM")
 * @property location Where the activity takes place
 * @property contactPerson Contact person for the activity
 * @property contactPhone Phone number of contact person
 */
data class Activity(
    val name: String,
    val schedule: String,
    val location: String? = null,
    val contactPerson: String? = null,
    val contactPhone: String? = null
)

/**
 * Emergency contact information.
 *
 * @property name Name of the contact person
 * @property relationship Relationship to the child
 * @property phone Phone number
 * @property alternatePhone Alternative phone number
 */
data class EmergencyContact(
    val name: String,
    val relationship: String,
    val phone: String,
    val alternatePhone: String? = null
)

/**
 * Information about the child's school.
 *
 * @property name Name of the school
 * @property address School address
 * @property phone School phone number
 * @property teacherName Name of the teacher
 * @property teacherEmail Teacher's email
 * @property grade Child's grade
 */
data class SchoolInfo(
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val teacherName: String? = null,
    val teacherEmail: String? = null,
    val grade: String? = null
)

