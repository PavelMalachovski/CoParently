package com.coparently.app.domain.receipts

import com.coparently.app.domain.model.ExpenseCategory
import java.time.LocalDate

/**
 * What could be read off a receipt photo. Every field is best-effort and may be null.
 *
 * @property total Amount to charge, as printed on the total line
 * @property currency ISO 4217 code detected on the receipt, e.g. "CZK"
 * @property merchant Shop name taken from the receipt header
 * @property date Purchase date
 * @property category Category guessed from keywords on the receipt
 */
data class ReceiptScan(
    val total: Double? = null,
    val currency: String? = null,
    val merchant: String? = null,
    val date: LocalDate? = null,
    val category: ExpenseCategory? = null
) {
    /** True when nothing usable was read, so the UI can say the receipt was unreadable. */
    val isEmpty: Boolean
        get() = total == null && merchant == null && date == null
}

/**
 * On-device optical character recognition over a receipt photo.
 *
 * The image is referenced by a URI string rather than an Android `Uri` so the domain layer
 * stays free of Android types, the same way [com.coparently.app.domain.repository.ReceiptStorage]
 * does.
 */
interface ReceiptTextRecognizer {

    /**
     * Recognises the text on the image behind [imageUri].
     *
     * @param imageUri Content or file URI string of a local image
     * @return Recognised lines, roughly top to bottom
     */
    suspend fun recognize(imageUri: String): List<String>
}
