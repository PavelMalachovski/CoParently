package com.coparently.app.data.local

import androidx.room.TypeConverter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Type converters for Room database.
 * Converts custom types to and from database-compatible types.
 */
class Converters {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Converts a LocalDateTime to a String for database storage.
     */
    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): String? {
        return value?.format(formatter)
    }

    /**
     * Converts a String to a LocalDateTime from database storage.
     */
    @TypeConverter
    fun toLocalDateTime(value: String?): LocalDateTime? {
        return value?.let { LocalDateTime.parse(it, formatter) }
    }
}

