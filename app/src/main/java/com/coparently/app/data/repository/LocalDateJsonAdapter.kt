package com.coparently.app.data.repository

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.time.LocalDate

/**
 * Reads and writes a [LocalDate] as an ISO string.
 *
 * Gson ships no adapter for `java.time`, and its reflective fallback serialises a `LocalDate`'s
 * private fields — a shape that is not a date to anything else and that Android's stricter
 * reflection rules can refuse outright. Every date this project sends to Firestore is already an
 * ISO string, so this keeps one representation rather than adding a second.
 */
class LocalDateJsonAdapter : JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {

    override fun serialize(
        src: LocalDate?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement = JsonPrimitive(src?.toString())

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): LocalDate? = json?.asString?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
}
