package com.coparently.app.domain.receipts

import java.text.Normalizer

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

    /** Lowercases and strips diacritics so "K ÚHRADĚ" and "k uhrade" compare equal. */
    internal fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

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
     * amount on the following line (some receipts print the label and the number separately),
     * and finally to the largest amount anywhere outside the VAT breakdown.
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
            lines.getOrNull(index + 1)
                ?.let { moneyOnLine(it).firstOrNull() }
                ?.let { return it }
        }

        return lines
            .filter { line -> EXCLUDED_KEYWORDS.none { it in normalise(line) } }
            .flatMap { moneyOnLine(it) }
            .maxOrNull()
    }
}
