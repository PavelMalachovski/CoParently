package com.coparently.app.data.mlkit

import android.content.Context
import android.net.Uri
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device receipt OCR backed by ML Kit's bundled Latin text recognizer.
 *
 * The photo never leaves the device.
 */
@Singleton
class MlKitReceiptTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : ReceiptTextRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(imageUri: String): List<String> {
        // InputImage.fromFilePath opens the URI through the ContentResolver and fully decodes
        // the JPEG into a Bitmap synchronously — a multi-hundred-millisecond stall and a large
        // allocation on a full-resolution camera photo. The interface promises a suspend
        // function safe to call from any dispatcher, so that blocking work is confined to
        // Dispatchers.IO here rather than relying on every caller to already be off the main
        // thread.
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.parse(imageUri)) }
        return recognizer.process(image).await()
            .textBlocks
            .flatMap { block -> block.lines }
            .map { line -> line.text }
    }
}
