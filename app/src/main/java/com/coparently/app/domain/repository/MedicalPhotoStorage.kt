package com.coparently.app.domain.repository

/**
 * Abstraction over remote binary storage for photographs attached to a child's medical notes.
 *
 * **A third interface rather than a third method on [EventImageStorage], because the shape is
 * genuinely different.** Both existing helpers derive one fixed object name from an entity id, so
 * an entity has at most one image — re-uploading replaces it, which is exactly right for a
 * receipt. A parent photographing a prescription, a rash and a vaccination card has three, and
 * they are not versions of one another. Every method here therefore takes a **photo** id
 * alongside the child's.
 *
 * The uploaded object is not encrypted. Nothing in this app is, and §0 of the design records
 * that as a decision: a false promise about medical images would be worse than none.
 */
interface MedicalPhotoStorage {

    /**
     * Uploads the image behind [localUri] as one of [childInfoId]'s medical photographs.
     *
     * @param childInfoId The child record the photograph belongs to.
     * @param photoId A fresh identifier for this photograph. Must be unguessable — see
     *   `FirebaseImageStorage` and `storage.rules` for why the object's path is doing work a
     *   read rule cannot do here.
     * @param localUri Content URI string of the picked image on this device.
     * @return Download URL of the uploaded image, to be stored on the child's record.
     */
    suspend fun uploadMedicalPhoto(childInfoId: String, photoId: String, localUri: String): String

    /**
     * Deletes one photograph.
     *
     * **Callers must delete before dropping the URL from the record, and must not drop it if
     * this fails.** A reference removed from a record whose object is still in the bucket is an
     * image nobody can see and nobody can delete, still readable by anyone who kept the link.
     *
     * Safe to call when the object is already gone.
     *
     * @param downloadUrl The URL stored on the record. The object is resolved from the URL rather
     *   than rebuilt from ids, so a photograph uploaded by a build that laid its paths out
     *   differently can still be deleted.
     */
    suspend fun deleteMedicalPhoto(downloadUrl: String)
}
