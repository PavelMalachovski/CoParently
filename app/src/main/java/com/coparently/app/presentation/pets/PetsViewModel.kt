package com.coparently.app.presentation.pets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.domain.model.Pet
import com.coparently.app.domain.repository.PetPhotoStorage
import com.coparently.app.domain.repository.PetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Why a photograph did not make it on or off the pet's record.
 *
 * A code, not a message — a ViewModel has no `Context` and must not acquire one to build
 * user-facing text. The screen resolves it in composable scope, same as
 * `MedicalPhotoError`.
 */
enum class PetPhotoError {
    /** The upload failed. The photograph is not on the record and nothing is in the bucket. */
    UPLOAD_FAILED,

    /**
     * The object could not be deleted, so its URL was **kept** on the record — dropping the
     * reference anyway would leave an image in the bucket nobody can see or delete.
     */
    DELETE_FAILED
}

/**
 * How a save ended, for the editor to act on.
 *
 * A one-shot signal rather than a state flag, because the editor's response is navigation —
 * something that must happen exactly once. The screen used to decide this for itself with a
 * `saveCompleted`/`isSaving` pair, and nothing ever cleared `isSaving`, so the guard
 * `saveCompleted && !isSaving` was false forever: the form froze, disabled and spinning, and
 * the only way out was the back arrow. The write itself had already landed in Room, which is
 * why backing out and re-entering showed the change — the reported "saving takes very long".
 */
enum class PetSaveOutcome {
    /** The pet is in Room. Photographs may still have failed; [PetPhotoError] reports that. */
    SAVED,

    /** Nothing was written. The editor keeps its contents so the user can try again. */
    FAILED
}

/**
 * ViewModel for managing pets.
 *
 * The list and the editor subscribe to **different** flows on purpose: [uiState] carries the
 * whole list for the list screen, while [currentPet] is fed only by [loadPetById]'s
 * per-id observation. The editor never reads the head of the list — that pattern is exactly
 * the `ChildInfoViewModel` defect CLAUDE.md documents, where any background emission
 * silently repointed the editor at the wrong record.
 */
@HiltViewModel
class PetsViewModel @Inject constructor(
    private val petRepository: PetRepository,
    private val petPhotoStorage: PetPhotoStorage,
    private val firebaseAuthService: FirebaseAuthService,
    private val analyticsManager: AnalyticsManager,
    private val crashlyticsManager: CrashlyticsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<PetsUiState>(PetsUiState.Loading)
    val uiState: StateFlow<PetsUiState> = _uiState.asStateFlow()

    /** The one pet the editor is open on, or null while nothing has loaded. */
    private val _currentPet = MutableStateFlow<Pet?>(null)
    val currentPet: StateFlow<Pet?> = _currentPet.asStateFlow()

    /** Why a photograph did not make it on or off the record, or null when nothing is wrong. */
    private val _photoError = MutableStateFlow<PetPhotoError?>(null)
    val photoError: StateFlow<PetPhotoError?> = _photoError.asStateFlow()

    /** Clears [photoError] once the screen has shown it. */
    fun clearPhotoError() {
        _photoError.value = null
    }

    /**
     * Emitted once per completed save.
     *
     * `extraBufferCapacity = 1` so an emission is never dropped when the editor is between
     * recompositions; a `MutableStateFlow` would instead re-deliver the last outcome to a
     * re-entering editor and navigate it straight back out.
     */
    private val _saveOutcome = MutableSharedFlow<PetSaveOutcome>(extraBufferCapacity = 1)
    val saveOutcome: SharedFlow<PetSaveOutcome> = _saveOutcome.asSharedFlow()

    init {
        loadPets()
    }

    /**
     * Loads all pets for the list screen.
     */
    fun loadPets() {
        viewModelScope.launch {
            _uiState.value = PetsUiState.Loading
            try {
                petRepository.getAllPets().collect { pets ->
                    _uiState.value = PetsUiState.Success(pets)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                _uiState.value = PetsUiState.Error(e.message ?: "Failed to load pets")
            }
        }
    }

    /**
     * Observes the one pet being edited, by id, for the editor's whole lifetime.
     */
    fun loadPetById(id: String) {
        viewModelScope.launch {
            petRepository.observePetById(id).collect { pet ->
                _currentPet.value = pet
            }
        }
    }

    /**
     * Creates or updates a pet.
     *
     * Takes the whole [Pet] built by copying the loaded snapshot ([currentPet]) — the same
     * rule the child and event editors follow, so fields the form does not surface are never
     * silently reset on save.
     *
     * @param pet The pet to persist, with all form fields already applied via `copy()`
     * @param isNewPet Whether this call creates a brand-new pet, for analytics only
     */
    fun upsertPet(pet: Pet, isNewPet: Boolean) {
        viewModelScope.launch {
            _saveOutcome.emit(if (persist(pet, isNewPet)) PetSaveOutcome.SAVED else PetSaveOutcome.FAILED)
        }
    }

    /**
     * Writes the pet, reporting whether it landed.
     *
     * @return true when Room holds the pet. A `false` means nothing was written at all — a
     *   failed Firestore upload does not count, because `PetRepositoryImpl` writes Room first
     *   and leaves the row in the unsynced outbox for the next sync.
     */
    private suspend fun persist(pet: Pet, isNewPet: Boolean): Boolean {
        return try {
            val currentUser = firebaseAuthService.getCurrentUser()
                ?: throw IllegalStateException("User not authenticated")

            val finalPet = pet.copy(
                createdByFirebaseUid = pet.createdByFirebaseUid ?: currentUser.uid,
                lastModifiedBy = currentUser.uid,
                syncedToFirestore = false
            )

            petRepository.upsertPet(finalPet)

            if (isNewPet) {
                analyticsManager.logPetAdded()
            } else {
                analyticsManager.logPetUpdated()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            crashlyticsManager.recordExceptionWithContext(
                e,
                // The id rather than the name, matching `ChildInfoViewModel`: custom keys are
                // uploaded and persist across the Crashlytics session, so nothing a family
                // typed belongs in one.
                mapOf("action" to "upsert_pet", "pet_id" to pet.id)
            )
            Log.e(TAG, "Saving pet ${pet.id} failed", e)
            false
        }
    }

    /**
     * Saves the pet, having first settled its photographs.
     *
     * Photographs are uploaded **on save**, never on pick — a user who backs out of the form
     * must leave nothing behind in the bucket. A removal deletes the object first and keeps
     * the URL if that fails. Same contract as the child record's medical photos; see
     * `ChildInfoViewModel.upsertChildInfoWithPhotos` for the full reasoning.
     *
     * @param pet The pet to save, carrying the photographs it already had.
     * @param isNewPet Whether this is an add rather than an edit.
     * @param newPhotoUris Content URIs of photographs picked on this device, not yet uploaded.
     * @param removedPhotoUrls URLs the user removed, to be deleted from the bucket.
     */
    fun upsertPetWithPhotos(
        pet: Pet,
        isNewPet: Boolean,
        newPhotoUris: List<String>,
        removedPhotoUrls: List<String>
    ) {
        viewModelScope.launch {
            val kept = pet.photos.filter { url ->
                url !in removedPhotoUrls || !deletePhoto(url)
            }
            val added = newPhotoUris.mapNotNull { uri -> uploadPhoto(pet.id, uri) }
            val saved = persist(pet.copy(photos = kept + added), isNewPet)
            _saveOutcome.emit(if (saved) PetSaveOutcome.SAVED else PetSaveOutcome.FAILED)
        }
    }

    /**
     * Deletes one photograph's object.
     *
     * @return true when the object is gone and its URL may be dropped from the record.
     */
    private suspend fun deletePhoto(url: String): Boolean = try {
        petPhotoStorage.deletePetPhoto(url)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        crashlyticsManager.recordExceptionWithContext(e, mapOf("action" to "delete_pet_photo"))
        Log.e(TAG, "Deleting a pet photo failed", e)
        _photoError.value = PetPhotoError.DELETE_FAILED
        false
    }

    /**
     * Uploads one picked photograph. The photo id is a fresh UUID and unguessable on
     * purpose — the Storage path is standing in for a read rule; see `storage.rules`.
     *
     * @return the download URL, or null when the upload failed.
     */
    private suspend fun uploadPhoto(petId: String, localUri: String): String? = try {
        petPhotoStorage.uploadPetPhoto(
            petId = petId,
            photoId = UUID.randomUUID().toString(),
            localUri = localUri
        )
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        crashlyticsManager.recordExceptionWithContext(
            e,
            mapOf("action" to "upload_pet_photo", "pet_id" to petId)
        )
        // Crashlytics writes nothing to logcat, so without this line an upload failure on a
        // device in front of you is undiagnosable — which is how the `pet_photos` Storage rule
        // gap stayed invisible. `Log.e` survives the R8 strip.
        Log.e(TAG, "Uploading a pet photo to pet_photos/$petId failed", e)
        _photoError.value = PetPhotoError.UPLOAD_FAILED
        null
    }

    /**
     * Deletes a pet.
     */
    fun deletePet(pet: Pet) {
        viewModelScope.launch {
            try {
                petRepository.deletePet(pet)
                analyticsManager.logPetDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                crashlyticsManager.recordExceptionWithContext(
                    e,
                    mapOf("action" to "delete_pet", "pet_id" to pet.id)
                )
                _uiState.value = PetsUiState.Error(e.message ?: "Failed to delete pet")
            }
        }
    }

    /**
     * Syncs pets with Firestore.
     */
    fun syncPets() {
        viewModelScope.launch {
            try {
                petRepository.pullOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                _uiState.value = PetsUiState.Error(e.message ?: "Failed to sync pets")
            }
        }
    }

    private companion object {
        const val TAG = "PetsViewModel"
    }
}

/**
 * UI state for the pets list screen.
 */
sealed class PetsUiState {
    data object Loading : PetsUiState()
    data class Success(val pets: List<Pet>) : PetsUiState()
    data class Error(val message: String) : PetsUiState()
}
