package com.coparently.app.data.remote.firebase

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.coparently.app.domain.repository.EventImageStorage
import com.coparently.app.domain.repository.MedicalPhotoStorage
import com.coparently.app.domain.repository.ReceiptStorage
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.ktx.storageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ReceiptStorage], [EventImageStorage] and [MedicalPhotoStorage] backed by Firebase Cloud
 * Storage.
 *
 * Images are downscaled and recompressed to JPEG before upload to keep uploads fast
 * and storage usage low; the resulting download URL (with access token) is what gets
 * stored in Firestore, so the other parent can load the photo directly.
 */
@Singleton
class FirebaseImageStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: FirebaseStorage
) : ReceiptStorage, EventImageStorage, MedicalPhotoStorage {

    override suspend fun uploadReceipt(expenseId: String, localUri: String): String =
        upload(receiptPath(expenseId), localUri)

    override suspend fun deleteReceipt(expenseId: String) = delete(receiptPath(expenseId))

    override suspend fun uploadEventImage(eventId: String, localUri: String): String =
        upload(eventImagePath(eventId), localUri)

    override suspend fun deleteEventImage(eventId: String) = delete(eventImagePath(eventId))

    override suspend fun uploadMedicalPhoto(
        childInfoId: String,
        photoId: String,
        localUri: String
    ): String = upload(medicalPhotoPath(childInfoId, photoId), localUri)

    /**
     * Resolves the object from its download URL rather than rebuilding the path from ids.
     *
     * A photograph uploaded by a build that laid its paths out differently is still deletable
     * this way, and the record stores the URL rather than the pair of ids — so rebuilding would
     * mean parsing the URL to get the ids back in order to construct the path the URL already
     * names.
     */
    override suspend fun deleteMedicalPhoto(downloadUrl: String) {
        val ref = try {
            storage.getReferenceFromUrl(downloadUrl)
        } catch (e: IllegalArgumentException) {
            // Not a URL from this bucket. This must fail rather than quietly succeed: the caller
            // drops the reference only once the object is gone, and reporting success here would
            // remove the reference while leaving whatever the URL points at exactly where it is.
            throw IOException("Not a Firebase Storage URL: $downloadUrl", e)
        }
        try {
            ref.delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }

    private suspend fun upload(path: String, localUri: String): String {
        val bytes = withContext(Dispatchers.IO) { compressImage(Uri.parse(localUri)) }
        val ref = storage.reference.child(path)
        val metadata = storageMetadata { contentType = "image/jpeg" }
        ref.putBytes(bytes, metadata).await()
        return ref.downloadUrl.await().toString()
    }

    private suspend fun delete(path: String) {
        try {
            storage.reference.child(path).delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }

    private fun receiptPath(expenseId: String) = "receipts/$expenseId.jpg"

    private fun eventImagePath(eventId: String) = "event_images/$eventId.jpg"

    /**
     * One object per photograph, under the child that owns it.
     *
     * [photoId] is a UUID the caller generates, and it is load-bearing rather than decorative —
     * see the `medical_photos` block in `storage.rules`.
     */
    private fun medicalPhotoPath(childInfoId: String, photoId: String) =
        "medical_photos/$childInfoId/$photoId.jpg"

    /**
     * Decodes the picked image with subsampling so full-resolution camera photos
     * never load entirely into memory, then re-encodes as JPEG.
     */
    private fun compressImage(uri: Uri): ByteArray {
        val resolver = context.contentResolver

        // `decodeStream` returns null by contract when inJustDecodeBounds is set — it only
        // fills in the Options. So the null check belongs on the *stream*; testing the decode
        // result instead rejected every image ever picked, which is why attaching a photo has
        // never worked. An undecodable file still fails, on the second pass below.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openStreamOrThrow(uri).use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = resolver.openStreamOrThrow(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Cannot decode image: $uri")

        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Opens [uri] for reading, failing loudly rather than returning null to the caller. */
    private fun ContentResolver.openStreamOrThrow(uri: Uri): InputStream =
        openInputStream(uri) ?: throw IOException("Cannot open image: $uri")

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= MAX_DIMENSION_PX || height / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val MAX_DIMENSION_PX = 1600
        const val JPEG_QUALITY = 85
    }
}
