package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.PetDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.PetEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestorePetDataSource
import com.coparently.app.data.sync.PetAudience
import com.coparently.app.domain.model.Medication
import com.coparently.app.domain.model.Pet
import com.coparently.app.domain.model.PetSpecies
import com.coparently.app.domain.model.Vaccination
import com.coparently.app.domain.repository.PetRepository
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [PetRepository].
 * Coordinates between the local database and Firestore for pets, mirroring
 * [ChildInfoRepositoryImpl]: Room is the source of truth, Firestore carries the record to
 * the co-parent, and `sharedWith` is derived at upload time by [PetAudience].
 *
 * All four mappers (entity/domain/document, both directions) live in this one file on
 * purpose — the child record's document map exists in seven places across two files, and
 * that duplication has already shipped bugs twice. Keep the pet document's schema here.
 */
@Singleton
class PetRepositoryImpl @Inject constructor(
    private val petDao: PetDao,
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestorePetDataSource: FirestorePetDataSource
) : PetRepository {

    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getAllPets(): Flow<List<Pet>> {
        return petDao.getAllPets().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPetById(id: String): Pet? {
        return petDao.getPetById(id)?.toDomain()
    }

    override fun observePetById(id: String): Flow<Pet?> {
        return petDao.observePetById(id).map { it?.toDomain() }
    }

    override suspend fun upsertPet(pet: Pet) {
        val entity = pet.toEntity()
        petDao.insertPet(entity)

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            val audience = PetAudience.entitled(
                userId = firebaseUser.uid,
                creatorUid = pet.createdByFirebaseUid,
                partnerId = currentPartnerId(firebaseUser.uid)
            )
            val result = firestorePetDataSource.upsertPet(pet.id, pet.toFirestoreMap(audience))

            // Mark as synced only when the remote write actually succeeded — the same rule
            // ChildInfoRepositoryImpl follows, so a failed write stays in the retry path.
            if (result.isSuccess) {
                petDao.updatePet(entity.copy(syncedToFirestore = true))
            }
        }
    }

    override suspend fun deletePet(pet: Pet) {
        petDao.deletePetById(pet.id)

        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            firestorePetDataSource.deletePet(pet.id)
        }
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val partnerId = currentPartnerId(firebaseUser.uid)

        // Upload before downloading, so a local edit is never overwritten by the pull.
        for (entity in petDao.getUnsyncedPets()) {
            val pet = entity.toDomain()
            val audience = PetAudience.entitled(
                userId = firebaseUser.uid,
                creatorUid = entity.createdByFirebaseUid,
                partnerId = partnerId
            )
            val result = firestorePetDataSource.upsertPet(entity.id, pet.toFirestoreMap(audience))
            if (result.isSuccess) {
                petDao.markAsSynced(entity.id)
            }
        }

        firestorePetDataSource.getPetsForParent(firebaseUser.uid)
            .catch { e -> android.util.Log.w("PetRepo", "Pet sync failed", e) }
            .collect { firestoreList ->
                for (firestoreData in firestoreList) {
                    val pet = firestoreData.toPet()
                    petDao.insertPet(pet.toEntity().copy(syncedToFirestore = true))
                    repairAudience(firebaseUser.uid, partnerId, firestoreData)
                }
            }
    }

    /**
     * Widens a document's `sharedWith` when the live audience has outgrown the stored one.
     *
     * This is the pet record's answer to the stale-audience trap (CLAUDE.md item 14): a pet
     * created while unpaired is uploaded with an audience of one uid, and nothing else ever
     * revisits it. Because this runs on every sync pull and compares against **live** pairing
     * state, a partner who arrives later is added on the owner's next sync — and it naturally
     * re-arms on re-pairing, with no marker to key. Only this user's own documents are
     * touched, and only the one field, via `update()` — never a full `set()`, which could
     * overwrite the co-parent's newer edit of the record itself.
     */
    private suspend fun repairAudience(
        userId: String,
        partnerId: String?,
        firestoreData: Map<String, Any?>
    ) {
        if (firestoreData["createdByFirebaseUid"] as? String != userId) return
        val stored = (firestoreData["sharedWith"] as? List<*>)?.filterIsInstance<String>()
            ?: emptyList()
        val entitled = PetAudience.entitled(userId, userId, partnerId)
        if (!stored.containsAll(entitled)) {
            val id = firestoreData["id"] as? String ?: return
            firestorePetDataSource.updatePet(
                id,
                mapOf("sharedWith" to (stored + entitled).distinct())
            )
        }
    }

    /** The signed-in user's current co-parent, or null when unpaired. */
    private suspend fun currentPartnerId(userId: String): String? =
        userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }

    /**
     * Converts PetEntity to domain Pet.
     */
    private fun PetEntity.toDomain(): Pet {
        return Pet(
            id = id,
            name = name,
            species = PetSpecies.fromStored(species),
            breed = breed,
            dateOfBirth = dateOfBirth,
            medications = gson.fromJson(medicationsJson, Array<Medication>::class.java)
                ?.toList() ?: emptyList(),
            vaccinations = gson.fromJson(vaccinationsJson, Array<Vaccination>::class.java)
                ?.toList() ?: emptyList(),
            specialNeeds = specialNeeds,
            feedingNotes = feedingNotes,
            vetName = vetName,
            vetPhone = vetPhone,
            photos = gson.fromJson(photosJson, Array<String>::class.java)?.toList() ?: emptyList(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdByFirebaseUid = createdByFirebaseUid,
            lastModifiedBy = lastModifiedBy,
            syncedToFirestore = syncedToFirestore
        )
    }

    /**
     * Converts domain Pet to PetEntity.
     */
    private fun Pet.toEntity(): PetEntity {
        return PetEntity(
            id = id,
            name = name,
            species = species.name,
            breed = breed,
            dateOfBirth = dateOfBirth,
            medicationsJson = gson.toJson(medications),
            vaccinationsJson = gson.toJson(vaccinations),
            specialNeeds = specialNeeds,
            feedingNotes = feedingNotes,
            vetName = vetName,
            vetPhone = vetPhone,
            photosJson = gson.toJson(photos),
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdByFirebaseUid = createdByFirebaseUid,
            lastModifiedBy = lastModifiedBy,
            syncedToFirestore = syncedToFirestore
        )
    }

    /**
     * Converts a Pet to a Firestore map.
     *
     * @param audience The `sharedWith` UIDs this write should publish to, from
     *   [PetAudience.entitled] — a parameter so every writer goes through the one policy.
     */
    private fun Pet.toFirestoreMap(audience: List<String>): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "species" to species.name,
            "breed" to breed,
            "dateOfBirth" to dateOfBirth?.format(formatter),
            "medications" to medications.map {
                mapOf(
                    "name" to it.name,
                    "dosage" to it.dosage,
                    "frequency" to it.frequency,
                    "notes" to it.notes
                )
            },
            "vaccinations" to vaccinations.map {
                mapOf(
                    "name" to it.name,
                    "date" to it.date?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
            },
            "specialNeeds" to specialNeeds,
            "feedingNotes" to feedingNotes,
            "vetName" to vetName,
            "vetPhone" to vetPhone,
            "photos" to photos,
            "createdAt" to createdAt.format(formatter),
            "updatedAt" to updatedAt.format(formatter),
            "createdByFirebaseUid" to createdByFirebaseUid,
            "lastModifiedBy" to lastModifiedBy,
            "sharedWith" to audience
        )
    }

    /**
     * Converts a Firestore map to a Pet.
     *
     * Defensive on every field the rule does not require: a document written by another
     * build must degrade to defaults rather than throw and poison the whole pull.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toPet(): Pet {
        return Pet(
            id = this["id"] as String,
            name = this["name"] as String,
            species = PetSpecies.fromStored(this["species"] as? String),
            breed = this["breed"] as? String,
            dateOfBirth = (this["dateOfBirth"] as? String)?.let {
                LocalDateTime.parse(it, formatter)
            },
            medications = (this["medications"] as? List<Map<String, Any?>>)?.mapNotNull {
                val name = it["name"] as? String ?: return@mapNotNull null
                Medication(
                    name = name,
                    dosage = it["dosage"] as? String ?: "",
                    frequency = it["frequency"] as? String ?: "",
                    notes = it["notes"] as? String
                )
            } ?: emptyList(),
            vaccinations = (this["vaccinations"] as? List<Map<String, Any?>>)?.mapNotNull {
                val name = it["name"] as? String ?: return@mapNotNull null
                Vaccination(
                    name = name,
                    date = (it["date"] as? String)?.let { date ->
                        runCatching { LocalDate.parse(date) }.getOrNull()
                    }
                )
            } ?: emptyList(),
            specialNeeds = this["specialNeeds"] as? String,
            feedingNotes = this["feedingNotes"] as? String,
            vetName = this["vetName"] as? String,
            vetPhone = this["vetPhone"] as? String,
            photos = (this["photos"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            createdAt = LocalDateTime.parse(this["createdAt"] as String, formatter),
            updatedAt = LocalDateTime.parse(this["updatedAt"] as String, formatter),
            createdByFirebaseUid = this["createdByFirebaseUid"] as? String,
            lastModifiedBy = this["lastModifiedBy"] as? String,
            syncedToFirestore = true
        )
    }
}
