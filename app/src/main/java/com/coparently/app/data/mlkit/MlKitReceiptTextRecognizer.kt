package com.coparently.app.data.mlkit

import android.content.Context
import android.net.Uri
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
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
        val image = InputImage.fromFilePath(context, Uri.parse(imageUri))
        return recognizer.process(image).await()
            .textBlocks
            .flatMap { block -> block.lines }
            .map { line -> line.text }
    }
}
