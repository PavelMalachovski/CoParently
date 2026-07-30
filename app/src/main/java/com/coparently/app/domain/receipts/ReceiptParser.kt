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
     * Lines that carry a total keyword (e.g. "celkem", "total") without also carrying an
     * excluded keyword (VAT breakdown, subtotal, ...). Shared by [findTotal] and [findCurrency]
     * so both agree on which line is "the" total line.
     *
     * @param lines Recognised receipt lines
     * @return Matching lines paired with their original index
     */
    private fun totalKeywordLines(lines: List<String>): List<IndexedValue<String>> =
        lines.withIndex().filter { (_, line) ->
            val text = normalise(line)
            TOTAL_KEYWORDS.any { it in text } && EXCLUDED_KEYWORDS.none { it in text }
        }

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
        val keywordLines = totalKeywordLines(lines)

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

    /** `day.month.year` or `day/month/year` with a 4-digit year, e.g. "12.07.2026". */
    private val DATE_DMY = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\b""")

    /** ISO `year-month-day`, e.g. "2026-07-12". */
    private val DATE_YMD = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")

    /** `day.month.year` or `day/month/year` with a 2-digit year, e.g. "12.07.26". */
    private val DATE_DMY_SHORT = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2})\b""")

    /** A receipt older than this is almost certainly a misread, not a real purchase. */
    private const val MAX_RECEIPT_AGE_YEARS = 2L

    /**
     * Whether [marker] occurs in [text] as a standalone token rather than as a substring of an
     * unrelated word (e.g. "zl" must not match inside "puzzle", "eur" must not match inside
     * "europe", "kc" must not match inside "kcal"). Boundaries are checked with
     * [Char.isLetterOrDigit] rather than regex `\b` because that Unicode-aware check is what
     * lets "zł" keep matching: `ł` is a letter, but Java/Kotlin's default (ASCII-only) `\w` does
     * not treat it as a word character, so `\b` would silently fail to match it.
     */
    private fun containsToken(text: String, marker: String): Boolean {
        var index = text.indexOf(marker)
        while (index >= 0) {
            val before = index - 1
            val after = index + marker.length
            val boundaryBefore = before < 0 || !text[before].isLetterOrDigit()
            val boundaryAfter = after >= text.length || !text[after].isLetterOrDigit()
            if (boundaryBefore && boundaryAfter) return true
            index = text.indexOf(marker, index + 1)
        }
        return false
    }

    /**
     * Searches already-normalised [text] for a currency marker.
     *
     * Alphabetic markers ("czk", "zł", ...) must match as a standalone token, checked via
     * [containsToken]. Currency symbols ("€", "$", "£") are not letters, so a word-boundary check
     * does not apply to them — plain substring containment is enough and correct.
     */
    private fun findCurrencyMarker(text: String): String? =
        CURRENCY_MARKERS.firstOrNull { (marker, _) ->
            if (marker.all { it.isLetter() }) containsToken(text, marker) else marker in text
        }?.second

    /**
     * Detects the currency printed on the receipt.
     *
     * Searched on the total line first (reusing the same total/excluded keyword rules as
     * [findTotal]), then anywhere else in the text — a marker on the actual total line should
     * beat a stray match in the item list, country-of-origin text or nutrition information.
     *
     * @param lines Recognised receipt lines
     * @return ISO 4217 code, or null when no marker is present
     */
    internal fun findCurrency(lines: List<String>): String? {
        val totalIndices = totalKeywordLines(lines).map { it.index }.toSet()

        val totalText = normalise(
            lines.filterIndexed { index, _ -> index in totalIndices }.joinToString(" ")
        )
        findCurrencyMarker(totalText)?.let { return it }

        val restText = normalise(
            lines.filterIndexed { index, _ -> index !in totalIndices }.joinToString(" ")
        )
        return findCurrencyMarker(restText)
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
            // Candidates are ordered by regex type (DMY, then ISO YMD, then short-year DMY),
            // not by where they appear in the line — a line matching more than one format keeps
            // that fixed precedence rather than reading left to right.
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
