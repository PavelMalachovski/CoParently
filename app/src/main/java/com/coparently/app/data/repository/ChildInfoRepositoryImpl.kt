package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.entity.ChildInfoEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.repository.ChildInfoRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ChildInfoRepository].
 * Coordinates between local database and Firestore for child information.
 */
@Singleton
class ChildInfoRepositoryImpl @Inject constructor(
    private val childInfoDao: ChildInfoDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreChildInfoDataSource: FirestoreChildInfoDataSource
) : ChildInfoRepository {

    private val gson = Gson()
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getAllChildInfo(): Flow<List<ChildInfo>> {
        return childInfoDao.getAllChildInfo().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getChildInfoById(id: String): ChildInfo? {
        return childInfoDao.getChildInfoById(id)?.toDomain()
    }

    override fun observeChildInfoById(id: String): Flow<ChildInfo?> {
        return childInfoDao.observeChildInfoById(id).map { it?.toDomain() }
    }

    override suspend fun upsertChildInfo(childInfo: ChildInfo) {
        val entity = childInfo.toEntity()
        childInfoDao.insertChildInfo(entity)

        // Sync to Firestore if authenticated
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            val childInfoData = childInfo.toFirestoreMap()
            firestoreChildInfoDataSource.upsertChildInfo(childInfo.id, childInfoData)

            // Mark as synced
            val syncedEntity = entity.copy(syncedToFirestore = true)
            childInfoDao.updateChildInfo(syncedEntity)
        }
    }

    override suspend fun deleteChildInfo(childInfo: ChildInfo) {
        childInfoDao.deleteChildInfoById(childInfo.id)

        // Delete from Firestore if authenticated
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            firestoreChildInfoDataSource.deleteChildInfo(childInfo.id)
        }
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        // Get unsynced local child info
        val unsyncedChildInfo = childInfoDao.getUnsyncedChildInfo()

        // Upload to Firestore
        for (entity in unsyncedChildInfo) {
            val childInfo = entity.toDomain()
            val childInfoData = childInfo.toFirestoreMap()
            val result = firestoreChildInfoDataSource.upsertChildInfo(entity.id, childInfoData)

            if (result.isSuccess) {
                childInfoDao.markAsSynced(entity.id)
            }
        }

        // Download from Firestore
        firestoreChildInfoDataSource.getChildInfoForParent(firebaseUser.uid).collect { firestoreList ->
            for (firestoreData in firestoreList) {
                val childInfo = firestoreData.toChildInfo()
                val entity = childInfo.toEntity().copy(syncedToFirestore = true)
                childInfoDao.insertChildInfo(entity)
            }
        }
    }

    /**
     * Converts ChildInfoEntity to domain ChildInfo.
     */
    private fun ChildInfoEntity.toDomain(): ChildInfo {
        return ChildInfo(
            id = id,
            childName = childName,
            dateOfBirth = dateOfBirth,
            medications = gson.fromJson(medicationsJson, Array<com.coparently.app.domain.model.Medication>::class.java).toList(),
            activities = gson.fromJson(activitiesJson, Array<com.coparently.app.domain.model.Activity>::class.java).toList(),
            allergies = gson.fromJson(allergiesJson, Array<String>::class.java).toList(),
            medicalNotes = medicalNotes,
            emergencyContacts = gson.fromJson(emergencyContactsJson, Array<com.coparently.app.domain.model.EmergencyContact>::class.java).toList(),
            schoolInfo = schoolInfoJson?.let { gson.fromJson(it, com.coparently.app.domain.model.SchoolInfo::class.java) },
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdByFirebaseUid = createdByFirebaseUid,
            lastModifiedBy = lastModifiedBy,
            syncedToFirestore = syncedToFirestore
        )
    }

    /**
     * Converts domain ChildInfo to ChildInfoEntity.
     */
    private fun ChildInfo.toEntity(): ChildInfoEntity {
        return ChildInfoEntity(
            id = id,
            childName = childName,
            dateOfBirth = dateOfBirth,
            medicationsJson = gson.toJson(medications),
            activitiesJson = gson.toJson(activities),
            allergiesJson = gson.toJson(allergies),
            medicalNotes = medicalNotes,
            emergencyContactsJson = gson.toJson(emergencyContacts),
            schoolInfoJson = schoolInfo?.let { gson.toJson(it) },
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdByFirebaseUid = createdByFirebaseUid,
            lastModifiedBy = lastModifiedBy,
            syncedToFirestore = syncedToFirestore
        )
    }

    /**
     * Converts ChildInfo to Firestore map.
     */
    private fun ChildInfo.toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "childName" to childName,
            "dateOfBirth" to dateOfBirth?.format(formatter),
            "medications" to medications.map { mapOf(
                "name" to it.name,
                "dosage" to it.dosage,
                "frequency" to it.frequency,
                "notes" to it.notes
            )},
            "activities" to activities.map { mapOf(
                "name" to it.name,
                "schedule" to it.schedule,
                "location" to it.location,
                "contactPerson" to it.contactPerson,
                "contactPhone" to it.contactPhone
            )},
            "allergies" to allergies,
            "medicalNotes" to medicalNotes,
            "emergencyContacts" to emergencyContacts.map { mapOf(
                "name" to it.name,
                "relationship" to it.relationship,
                "phone" to it.phone,
                "alternatePhone" to it.alternatePhone
            )},
            "schoolInfo" to schoolInfo?.let { mapOf(
                "name" to it.name,
                "address" to it.address,
                "phone" to it.phone,
                "teacherName" to it.teacherName,
                "teacherEmail" to it.teacherEmail,
                "grade" to it.grade
            )},
            "createdAt" to createdAt.format(formatter),
            "updatedAt" to updatedAt.format(formatter),
            "createdByFirebaseUid" to createdByFirebaseUid,
            "lastModifiedBy" to lastModifiedBy,
            "sharedWith" to listOfNotNull(createdByFirebaseUid, lastModifiedBy).distinct()
        )
    }

    /**
     * Converts Firestore map to ChildInfo.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toChildInfo(): ChildInfo {
        return ChildInfo(
            id = this["id"] as String,
            childName = this["childName"] as String,
            dateOfBirth = (this["dateOfBirth"] as? String)?.let { LocalDateTime.parse(it, formatter) },
            medications = (this["medications"] as? List<Map<String, Any?>>)?.map {
                com.coparently.app.domain.model.Medication(
                    name = it["name"] as String,
                    dosage = it["dosage"] as String,
                    frequency = it["frequency"] as String,
                    notes = it["notes"] as? String
                )
            } ?: emptyList(),
            activities = (this["activities"] as? List<Map<String, Any?>>)?.map {
                com.coparently.app.domain.model.Activity(
                    name = it["name"] as String,
                    schedule = it["schedule"] as String,
                    location = it["location"] as? String,
                    contactPerson = it["contactPerson"] as? String,
                    contactPhone = it["contactPhone"] as? String
                )
            } ?: emptyList(),
            allergies = (this["allergies"] as? List<String>) ?: emptyList(),
            medicalNotes = this["medicalNotes"] as? String,
            emergencyContacts = (this["emergencyContacts"] as? List<Map<String, Any?>>)?.map {
                com.coparently.app.domain.model.EmergencyContact(
                    name = it["name"] as String,
                    relationship = it["relationship"] as String,
                    phone = it["phone"] as String,
                    alternatePhone = it["alternatePhone"] as? String
                )
            } ?: emptyList(),
            schoolInfo = (this["schoolInfo"] as? Map<String, Any?>)?.let {
                com.coparently.app.domain.model.SchoolInfo(
                    name = it["name"] as String,
                    address = it["address"] as? String,
                    phone = it["phone"] as? String,
                    teacherName = it["teacherName"] as? String,
                    teacherEmail = it["teacherEmail"] as? String,
                    grade = it["grade"] as? String
                )
            },
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            lastModifiedBy = this["lastModifiedBy"] as? String,
            syncedToFirestore = true
        )
    }
}

