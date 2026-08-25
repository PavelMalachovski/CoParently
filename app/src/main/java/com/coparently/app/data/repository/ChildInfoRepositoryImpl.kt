package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.ChildInfoEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.data.sync.ChildInfoAudience
import com.coparently.app.data.sync.ChildInfoGuests
import com.coparently.app.data.sync.ChildInfoPhotos
import com.coparently.app.domain.family.FamilyKey
import com.coparently.app.domain.guests.GuestGrantPolicy
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.repository.ChildInfoRepository
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
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
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreChildInfoDataSource: FirestoreChildInfoDataSource
) : ChildInfoRepository {

    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()
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
        val firebaseUser = firebaseAuthService.getCurrentUser()
        val partnerId = firebaseUser?.let { currentPartnerId(it.uid) }
        // Which relationship this child belongs to, and therefore whose family screens list
        // them. Null while the account is unpaired: the record is this parent's alone until
        // there is somebody to share it with. Kept once stamped, so a re-pairing to a
        // different co-parent cannot silently move a child between families.
        val owned = childInfo.copy(
            familyId = childInfo.familyId ?: FamilyKey.orNull(firebaseUser?.uid, partnerId)
        )
        val entity = owned.toEntity()
        childInfoDao.insertChildInfo(entity)

        // Sync to Firestore if authenticated
        if (firebaseUser != null) {
            val audience = ChildInfoAudience.entitled(
                userId = firebaseUser.uid,
                creatorUid = owned.createdByFirebaseUid,
                partnerId = partnerId,
                guestUids = activeGuestUids(owned)
            )
            val childInfoData = owned.toFirestoreMap(audience)
            val result = firestoreChildInfoDataSource.upsertChildInfo(owned.id, childInfoData)

            // Mark as synced only when the remote write actually succeeded. Marking it
            // unconditionally (the previous behaviour) stranded a failed write out of
            // `getUnsyncedChildInfo()`'s retry path forever, with nothing left to say the
            // document never reached Firestore.
            if (result.isSuccess) {
                val syncedEntity = entity.copy(syncedToFirestore = true)
                childInfoDao.updateChildInfo(syncedEntity)
            }
        }
    }

    /**
     * The signed-in user's current co-parent, or null when unpaired.
     *
     * Shared by [upsertChildInfo] and [pullOnce] so both writers ask the same
     * question the same way before handing the answer to [ChildInfoAudience.entitled].
     */
    private suspend fun currentPartnerId(userId: String): String? =
        userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }

    override suspend fun deleteChildInfo(childInfo: ChildInfo) {
        childInfoDao.deleteChildInfoById(childInfo.id)

        // Delete from Firestore if authenticated
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            firestoreChildInfoDataSource.deleteChildInfo(childInfo.id)
        }
    }

    override suspend fun pullOnce() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val partnerId = currentPartnerId(firebaseUser.uid)

        // Get unsynced local child info
        val unsyncedChildInfo = childInfoDao.getUnsyncedChildInfo()

        // Upload to Firestore
        for (entity in unsyncedChildInfo) {
            val childInfo = entity.toDomain()
            val audience = ChildInfoAudience.entitled(
                userId = firebaseUser.uid,
                creatorUid = entity.createdByFirebaseUid,
                partnerId = partnerId,
                guestUids = activeGuestUids(childInfo)
            )
            val childInfoData = childInfo.toFirestoreMap(audience)
            val result = firestoreChildInfoDataSource.upsertChildInfo(entity.id, childInfoData)

            if (result.isSuccess) {
                childInfoDao.markAsSynced(entity.id)
            }
        }

        // Download from Firestore
        firestoreChildInfoDataSource.getChildInfoForParent(firebaseUser.uid)
            .catch { e -> android.util.Log.w("ChildInfoRepo", "Child info sync failed", e) }
            .collect { firestoreList ->
                for (firestoreData in firestoreList) {
                    val childInfo = firestoreData.toChildInfo()
                    val entity = childInfo.toEntity().copy(syncedToFirestore = true)
                    childInfoDao.insertChildInfo(entity)
                }
            }
    }

    /**
     * The uids of [childInfo]'s guests whose grants are still good.
     *
     * Filtered rather than passed whole, so an expired guest leaves `sharedWith` on the very next
     * upload rather than waiting for the scheduled sweep. That is the sweep's work being done
     * early, which is the direction to fail in; the reverse — a guest whose grant has run out
     * still sitting in the audience — is what the sweep exists to clean up.
     */
    private fun activeGuestUids(childInfo: ChildInfo): List<String> =
        GuestGrantPolicy.active(childInfo.guests.values.toList(), Instant.now()).map { it.uid }

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
            medicalProfile = (
                gson.fromJson(medicalProfileJson, MedicalProfile::class.java) ?: MedicalProfile()
                ).withSanitizedVaccinationNames(),
            medicalPhotos = ChildInfoPhotos.decode(
                gson.fromJson(medicalPhotosJson, Array<String>::class.java)?.toList()
            ),
            guests = ChildInfoGuests.decode(gson.fromJson(guestsJson, Map::class.java)),
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdByFirebaseUid = createdByFirebaseUid,
            lastModifiedBy = lastModifiedBy,
            syncedToFirestore = syncedToFirestore,
            familyId = familyId
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
            medicalProfileJson = gson.toJson(medicalProfile),
            medicalPhotosJson = gson.toJson(medicalPhotos),
            guestsJson = gson.toJson(ChildInfoGuests.encode(guests)),
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdByFirebaseUid = createdByFirebaseUid,
            lastModifiedBy = lastModifiedBy,
            syncedToFirestore = syncedToFirestore,
            familyId = familyId
        )
    }

    /**
     * Converts ChildInfo to a Firestore map.
     *
     * @param audience The `sharedWith` UIDs this write should publish to, from
     *   [ChildInfoAudience.entitled]. Taken as a parameter rather than derived in here so
     *   both callers ([upsertChildInfo] and [pullOnce]) go through the single
     *   policy instead of each computing their own.
     */
    private fun ChildInfo.toFirestoreMap(audience: List<String>): Map<String, Any?> {
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
            "medicalProfile" to gson.fromJson(gson.toJson(medicalProfile), Map::class.java),
            "medicalPhotos" to medicalPhotos,
            "guests" to ChildInfoGuests.encode(guests),
            "createdAt" to createdAt.format(formatter),
            "updatedAt" to updatedAt.format(formatter),
            "createdByFirebaseUid" to createdByFirebaseUid,
            "lastModifiedBy" to lastModifiedBy,
            "sharedWith" to audience,
            "familyId" to (familyId ?: "")
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
            medicalProfile = (
                (this["medicalProfile"] as? Map<*, *>)?.let {
                    gson.fromJson(gson.toJson(it), MedicalProfile::class.java)
                } ?: MedicalProfile()
                ).withSanitizedVaccinationNames(),
            medicalPhotos = ChildInfoPhotos.decode(this["medicalPhotos"]),
            guests = ChildInfoGuests.decode(this["guests"]),
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            lastModifiedBy = this["lastModifiedBy"] as? String,
            syncedToFirestore = true,
            familyId = (this["familyId"] as? String)?.takeIf { it.isNotEmpty() }
        )
    }
}

