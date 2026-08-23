package com.coparently.app.data.repository

import com.coparently.app.domain.custody.DayOverride
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The Room mirror's encoding of [DayOverride]s, keyed by ISO date.
 *
 * Room stores them as one nullable JSON column rather than a table of their own: they are read
 * and written as a whole map every time — the shared document carries them as one field, and a
 * write replaces all of them — so a second table would buy nothing but a join and a migration.
 * The same shape `UserEntity` uses for allergies and the medical profile.
 *
 * **Null and `{}` both mean "none", and encoding chooses null.** A row that has never carried a
 * swap must be byte-identical to one written before the column existed, because
 * `CustodyModelRepository.mirrorIntoRoom` compares whole entities to decide whether to re-insert
 * — and a re-insert ticks Room's invalidation tracker, re-emits to every observer, and does it
 * again on the next Firestore echo.
 */
object DayOverrideJson {

    private val gson = Gson()
    private val type = object : TypeToken<Map<String, DayOverride>>() {}.type

    /** [overrides] as JSON, or null when there are none. */
    fun encode(overrides: Map<String, DayOverride>): String? =
        overrides.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }

    /**
     * [json] as a map, or empty when it is null, blank or unreadable.
     *
     * Unreadable degrades to "no swaps" rather than throwing: this is on the path that paints
     * the calendar, and a malformed column must cost the swaps, not the grid. The document
     * remains the real record, so the next mirror pass restores them.
     */
    fun decode(json: String?): Map<String, DayOverride> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<String, DayOverride>>(json, type) }
            .getOrNull()
            .orEmpty()
    }
}
