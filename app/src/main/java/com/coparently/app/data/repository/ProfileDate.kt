package com.coparently.app.data.repository

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads a stored profile date.
 *
 * `UserEntity.dateOfBirth` is an ISO string, not a converted `LocalDateTime`: a birth date has no
 * time of day, and `Converters` carries no `LocalDate` converter to borrow.
 *
 * A value that will not parse degrades to null rather than throwing. Every screen that shows the
 * signed-in user reads this row, so an exception here would not spoil a date field — it would
 * empty the app.
 *
 * @param stored The raw column value, or null
 * @return The date, or null when absent, blank or unparseable
 */
fun parseProfileDate(stored: String?): LocalDate? {
    val trimmed = stored?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed)
    } catch (e: DateTimeParseException) {
        null
    }
}
