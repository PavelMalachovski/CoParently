package com.coparently.app.domain.receipts

import com.coparently.app.domain.model.ExpenseCategory
import java.text.Normalizer
import java.time.LocalDate

/**
 * Turns the raw lines recognised on a receipt into structured fields.
 *
 * Pure and deterministic: every heuristic here is covered by unit tests, because OCR output
 * varies wildly between shops and this is where the feature is most likely to be wrong.
 */
// Each field (total, currency, date, merchant, category) gets its own function on purpose — that
// is what lets every heuristic be reasoned about and unit-tested independently, which is the
// whole design of this parser. Splitting the object to dodge the function count would work
// against that.
@Suppress("TooManyFunctions")
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

    /** Whether [char] can appear in a money-shaped token: a digit, or one of the two separators. */
    private fun isMoneyCharacter(char: Char): Boolean = char.isDigit() || char == '.' || char == ','

    /**
     * A tail exactly this long after the last separator marks thousands grouping (e.g. the
     * ".234" in "1.234", read as 1234) rather than a decimal fraction — real money is never
     * quoted to three decimal places.
     */
    private const val THOUSANDS_GROUPING_TAIL_LENGTH = 3

    /**
     * Thousands grouping only makes sense with a head this short (e.g. "1", "12" or "123" before
     * the separator, as in "1.234"). A longer head isn't a single group of digits, so the token
     * is rejected instead of being misread as a decimal amount.
     */
    private const val MAX_THOUSANDS_GROUPING_HEAD_LENGTH = 3

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
        if (cleaned.isEmpty() || !cleaned.all(::isMoneyCharacter)) return null

        val separatorIndex = maxOf(cleaned.lastIndexOf('.'), cleaned.lastIndexOf(','))
        if (separatorIndex < 0) return cleaned.toDoubleOrNull()

        val head = cleaned.substring(0, separatorIndex)
        val tail = cleaned.substring(separatorIndex + 1)
        val headDigits = head.filter { it.isDigit() }

        return when {
            tail.length in 1..2 -> "$headDigits.$tail".toDoubleOrNull()
            tail.length == THOUSANDS_GROUPING_TAIL_LENGTH &&
                headDigits.length in 1..MAX_THOUSANDS_GROUPING_HEAD_LENGTH ->
                (headDigits + tail).toDoubleOrNull()
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
     * Two-digit receipt years are assumed to fall in the 2000s (e.g. "26" reads as 2026) —
     * there is no plausible retail receipt from the 1900s.
     */
    private const val ASSUMED_CENTURY_BASE = 2000

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
                    add(Triple(ASSUMED_CENTURY_BASE + year.toInt(), month.toInt(), day.toInt()))
                }
            }

            candidates.forEach { (year, month, day) ->
                val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                if (date != null && !date.isAfter(today) && !date.isBefore(oldest)) return date
            }
        }
        return null
    }

    /** Header lines that are never the shop's name. */
    private val MERCHANT_STOPWORDS = listOf(
        "ico", "dic", "tel", "www", "http", "ulice", "namesti", "psc",
        "danovy doklad", "uctenka", "receipt", "faktura", "pokladna"
    )

    private const val MERCHANT_MIN_LENGTH = 3
    private const val MERCHANT_MAX_LENGTH = 40

    /**
     * A merchant-name candidate line is rejected once at least a third of its characters are
     * digits — that ratio is the signature of a phone number, a registration number
     * (e.g. "ICO 12345678") or a street address, never a shop name.
     */
    private const val MERCHANT_DIGIT_HEAVY_RATIO = 3

    /** Keyword to category, first match wins. */
    private val CATEGORY_KEYWORDS = listOf(
        "lekarna" to ExpenseCategory.MEDICAL,
        "pharmacy" to ExpenseCategory.MEDICAL,
        "apotheke" to ExpenseCategory.MEDICAL,
        "zubni" to ExpenseCategory.MEDICAL,
        "skolka" to ExpenseCategory.EDUCATION,
        "skolne" to ExpenseCategory.EDUCATION,
        "skola" to ExpenseCategory.EDUCATION,
        "krouzek" to ExpenseCategory.EDUCATION,
        "tuition" to ExpenseCategory.EDUCATION,
        "potraviny" to ExpenseCategory.FOOD,
        "restaurace" to ExpenseCategory.FOOD,
        "kavarna" to ExpenseCategory.FOOD,
        "bistro" to ExpenseCategory.FOOD,
        "cafe" to ExpenseCategory.FOOD,
        "obleceni" to ExpenseCategory.CLOTHING,
        "obuv" to ExpenseCategory.CLOTHING,
        "clothing" to ExpenseCategory.CLOTHING,
        "hracky" to ExpenseCategory.TOYS,
        "toys" to ExpenseCategory.TOYS,
        "jizdenka" to ExpenseCategory.TRANSPORTATION,
        "benzin" to ExpenseCategory.TRANSPORTATION,
        "nafta" to ExpenseCategory.TRANSPORTATION,
        "fuel" to ExpenseCategory.TRANSPORTATION,
        "drogerie" to ExpenseCategory.HOUSEHOLD,
        "household" to ExpenseCategory.HOUSEHOLD
    )

    /**
     * Picks the shop name: the first header line that reads like a name rather than an address,
     * a registration number or a phone number.
     *
     * Returned verbatim apart from whitespace collapsing — receipt headers are usually already
     * capitalised the way the shop writes its name.
     *
     * @param lines Recognised receipt lines
     * @return The merchant name, or null when no line qualifies
     */
    internal fun findMerchant(lines: List<String>): String? = lines.asSequence()
        .map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.length in MERCHANT_MIN_LENGTH..MERCHANT_MAX_LENGTH }
        .filter { line -> line.any { it.isLetter() } }
        .filter { line ->
            val text = normalise(line)
            MERCHANT_STOPWORDS.none { it in text }
        }
        .filterNot { line -> line.count { it.isDigit() } * MERCHANT_DIGIT_HEAVY_RATIO > line.length }
        .firstOrNull()

    /**
     * Guesses a category from keywords anywhere on the receipt.
     *
     * @param lines Recognised receipt lines
     * @return The matched category, or null when nothing matched
     */
    internal fun findCategory(lines: List<String>): ExpenseCategory? {
        val text = normalise(lines.joinToString(" "))
        return CATEGORY_KEYWORDS.firstOrNull { (keyword, _) -> keyword in text }?.second
    }

    /**
     * Parses recognised receipt lines into structured fields.
     *
     * @param lines Recognised receipt lines, roughly top to bottom
     * @param today Reference date used to sanity-check the purchase date
     * @return Best-effort fields; any of them may be null
     */
    fun parse(lines: List<String>, today: LocalDate = LocalDate.now()): ReceiptScan = ReceiptScan(
        total = findTotal(lines),
        currency = findCurrency(lines),
        merchant = findMerchant(lines),
        date = findDate(lines, today),
        category = findCategory(lines)
    )
}
