package com.coparently.app.domain.receipts

import java.text.Normalizer
import java.time.LocalDate

/**
 * Turns the raw lines recognised on a receipt into structured fields.
 *
 * Pure and deterministic: every heuristic here is covered by unit tests, because OCR output
 * varies wildly between shops and this is where the feature is most likely to be wrong.
 */
object ReceiptParser {

    /** Lines whose text marks the amount actually charged. */
    private val TOTAL_KEYWORDS = listOf("celkem", "k uhrade", "suma", "total", "amount due")

    /**
     * Lines that hold money but never the total: the VAT breakdown, the taxable base,
     * rounding lines and subtotals. Without excluding these, the VAT table routinely
     * beats the real total.
     */
    private val EXCLUDED_KEYWORDS =
        listOf("dph", "vat", "zaklad", "zaokr", "subtotal", "mezisoucet")

    /** A run of digits possibly grouped by spaces, dots or commas. */
    private val MONEY_REGEX = Regex("""\d[\d  .,]*""")

    /** Combining diacritical marks left behind by NFD normalisation. */
    private val COMBINING_MARKS_REGEX = Regex("""\p{Mn}+""")

    /** Lowercases and strips diacritics so "K ÚHRADĚ" and "k uhrade" compare equal. */
    internal fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")

    /**
     * Parses one money-shaped token.
     *
     * Handles `12.99`, `349,50`, `1 234,50`, `1.234,50` and `1.234`. When both separators are
     * present the last one is the decimal separator. A three-digit tail is treated as thousands
     * grouping; anything longer is not money (dates and item codes land here).
     *
     * @param raw Token as printed on the receipt
     * @return The amount, or null when the token is not money
     */
    internal fun parseMoney(raw: String): Double? {
        val cleaned = raw.replace(" ", "").replace(" ", "").trim('.', ',')
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it == '.' || it == ',' }) return null

        val separatorIndex = maxOf(cleaned.lastIndexOf('.'), cleaned.lastIndexOf(','))
        if (separatorIndex < 0) return cleaned.toDoubleOrNull()

        val head = cleaned.substring(0, separatorIndex)
        val tail = cleaned.substring(separatorIndex + 1)
        val headDigits = head.filter { it.isDigit() }

        return when {
            tail.length in 1..2 -> "$headDigits.$tail".toDoubleOrNull()
            tail.length == 3 && headDigits.length in 1..3 -> (headDigits + tail).toDoubleOrNull()
            else -> null
        }
    }

    /** All money-shaped amounts on one line, in reading order. */
    private fun moneyOnLine(line: String): List<Double> =
        MONEY_REGEX.findAll(line).mapNotNull { parseMoney(it.value) }.toList()

    /**
     * Finds the amount charged.
     *
     * Prefers the last amount on a line carrying a total keyword, falling back to the first
     * amount on the next non-excluded line below it (some receipts print the label and the
     * number separately, sometimes with a VAT breakdown printed in between), and finally to the
     * largest amount anywhere outside the VAT breakdown.
     *
     * @param lines Recognised receipt lines
     * @return The total, or null when the receipt holds no money at all
     */
    internal fun findTotal(lines: List<String>): Double? {
        val keywordLines = lines.withIndex().filter { (_, line) ->
            val text = normalise(line)
            TOTAL_KEYWORDS.any { it in text } && EXCLUDED_KEYWORDS.none { it in text }
        }

        for ((index, line) in keywordLines) {
            moneyOnLine(line).lastOrNull()?.let { return it }
            lines.drop(index + 1)
                .firstOrNull { candidate -> EXCLUDED_KEYWORDS.none { it in normalise(candidate) } }
                ?.let { candidate -> moneyOnLine(candidate).firstOrNull() }
                ?.let { return it }
        }

        return lines
            .filter { line -> EXCLUDED_KEYWORDS.none { it in normalise(line) } }
            .flatMap { moneyOnLine(it) }
            .maxOrNull()
    }

    /**
     * Currency markers searched in normalised text. "Kč" normalises to "kc"; "zł" keeps its
     * stroked l because NFD does not decompose it, so both spellings are listed.
     */
    private val CURRENCY_MARKERS = listOf(
        "czk" to "CZK", "kc" to "CZK",
        "eur" to "EUR", "€" to "EUR",
        "pln" to "PLN", "zł" to "PLN", "zl" to "PLN",
        "usd" to "USD", "$" to "USD",
        "gbp" to "GBP", "£" to "GBP"
    )

    private val DATE_DMY = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\b""")
    private val DATE_YMD = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val DATE_DMY_SHORT = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2})\b""")

    /** A receipt older than this is almost certainly a misread, not a real purchase. */
    private const val MAX_RECEIPT_AGE_YEARS = 2L

    /**
     * Detects the currency printed on the receipt.
     *
     * @param lines Recognised receipt lines
     * @return ISO 4217 code, or null when no marker is present
     */
    internal fun findCurrency(lines: List<String>): String? {
        val text = normalise(lines.joinToString(" "))
        return CURRENCY_MARKERS.firstOrNull { (marker, _) -> marker in text }?.second
    }

    /**
     * Detects the purchase date.
     *
     * Dates in the future or more than [MAX_RECEIPT_AGE_YEARS] old are rejected — that guard is
     * what stops a card expiry or a "platnost do" line being read as the purchase date.
     *
     * @param lines Recognised receipt lines
     * @param today Reference date; injected so the tests are deterministic
     * @return The purchase date, or null when nothing plausible was found
     */
    internal fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        val oldest = today.minusYears(MAX_RECEIPT_AGE_YEARS)

        for (line in lines) {
            val candidates = buildList {
                DATE_DMY.findAll(line).forEach { match ->
                    val (day, month, year) = match.destructured
                    add(Triple(year.toInt(), month.toInt(), day.toInt()))
                }
                DATE_YMD.findAll(line).forEach { match ->
                    val (year, month, day) = match.destructured
                    add(Triple(year.toInt(), month.toInt(), day.toInt()))
                }
                DATE_DMY_SHORT.findAll(line).forEach { match ->
                    val (day, month, year) = match.destructured
                    add(Triple(2000 + year.toInt(), month.toInt(), day.toInt()))
                }
            }

            candidates.forEach { (year, month, day) ->
                val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                if (date != null && !date.isAfter(today) && !date.isBefore(oldest)) return date
            }
        }
        return null
    }
}
