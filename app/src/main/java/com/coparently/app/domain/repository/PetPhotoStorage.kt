package com.coparently.app.domain.repository

/**
 * Abstraction over remote binary storage for a pet's photographs.
 *
 * The same shape as [MedicalPhotoStorage] — a pet has several photographs and they are not
 * versions of one another, so every method takes a **photo** id alongside the pet's. A
 * separate interface rather than a fourth method on an existing one for the same reason
 * [MedicalPhotoStorage] is a third: a caller that attaches pet photographs should not be
 * handed the ability to delete receipts or medical images.
 */
interface PetPhotoStorage {

    /**
     * Uploads the image behind [localUri] as one of [petId]'s photographs.
     *
     * @param petId The pet record the photograph belongs to.
     * @param photoId A fresh identifier for this photograph. Must be unguessable — the
     *   Storage read rule cannot tell one signed-in user from another, so the path is doing
     *   the work; see the `pet_photos` block in `storage.rules`.
     * @param localUri Content URI string of the picked image on this device.
     * @return Download URL of the uploaded image, to be stored on the pet's record.
     */
    suspend fun uploadPetPhoto(petId: String, photoId: String, localUri: String): String

    /**
     * Deletes one photograph.
     *
     * **Callers must delete before dropping the URL from the record, and must not drop it if
     * this fails** — the same contract as [MedicalPhotoStorage.deleteMedicalPhoto], for the
     * same reason: a dropped reference to a surviving object is an image nobody can see and
     * nobody can delete.
     *
     * Safe to call when the object is already gone.
     *
     * @param downloadUrl The URL stored on the record.
     */
    suspend fun deletePetPhoto(downloadUrl: String)
}
