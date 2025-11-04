package com.coparently.app.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Type converters for Room database.
 * Converts custom types to and from database-compatible types.
 */
class Converters {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val gson = Gson()

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

    /**
     * Converts a List<String> to a JSON String for database storage.
     */
    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return gson.toJson(value ?: emptyList<String>())
    }

    /**
     * Converts a JSON String to a List<String> from database storage.
     */
    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType)
    }
}

