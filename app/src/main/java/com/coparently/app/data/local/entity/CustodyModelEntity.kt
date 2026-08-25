package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a custody model configuration.
 * Supports various custody patterns including:
 * - Week on/week off (alternating weeks)
 * - Custom patterns (e.g., 2-2-3 split)
 * - The pattern repeats throughout the year
 *
 * @property id Unique identifier for the custody model
 * @property modelType Type of custody model: "week_on_week_off", "2_2_3", "3_4_4_3", "custom"
 * @property patternDays Number of days in the complete pattern cycle (e.g., 14 for week-on-week-off)
 * @property momDaysPattern JSON array of day indices (0-based) when mom has custody within the pattern
 * @property startDate ISO date string for when this pattern starts (anchor date for calculation)
 * @property isActive Whether this model is currently active
 * @property repeatYearly Whether this pattern repeats yearly (always true for MVP)
 * @property createdAt ISO date-time string when this model was created
 * @property lastModifiedAt Dead column — see the property's own note. Superseded by
 * [lastModifiedAtMillis].
 * @property lastModifiedAtMillis When this model was last modified, epoch millis
 * @property dayOverridesJson JSON object of one-off day swaps keyed by ISO date, mirroring the
 * shared document's `dayOverrides`; null on a row that predates the field and on any row that
 * has never carried a swap
 */
@Entity(tableName = "custody_models")
data class CustodyModelEntity(
    @PrimaryKey
    val id: String,
    val modelType: String, // "week_on_week_off", "2_2_3", "3_4_4_3", "custom"
    val patternDays: Int, // Total days in pattern cycle
    val momDaysPattern: String, // JSON array: [0,1,2,3,4,5,6] means mom has days 1-7 of pattern
    val startDate: String, // ISO date string - anchor date for pattern calculation
    val isActive: Boolean = true,
    val repeatYearly: Boolean = true,
    val createdAt: String,
    /**
     * Dead column, kept because dropping one needs a table rebuild that cannot be tested here.
     *
     * Superseded by [lastModifiedAtMillis] in schema 29. It held a naive `LocalDateTime` — no
     * zone, no offset — and it decided which phone's schedule survived, which is SEC-4. Room
     * compares the whole table against the entity, so leaving the column in the database while
     * removing it from the class fails validation; the alternative is a rebuild, and
     * `app/schemas/` stops at v14 (CQ-1), so `MigrationTestHelper` cannot build a v28 database
     * to prove one against. Nothing reads it. Delete it when CQ-1 lands.
     */
    val lastModifiedAt: String = "",
    /**
     * When this model was last modified, epoch millis.
     *
     * `CustodyModelRepository.isNewer` compares it, and the side it judges newer is re-pushed
     * over the other — so it has to mean the same thing on both parents' phones. Zero means
     * undated and loses every comparison; see [com.coparently.app.domain.custody.CustodyTimestamp].
     */
    val lastModifiedAtMillis: Long = 0L,
    /**
     * JSON object of one-off day swaps keyed by ISO date, mirroring the shared document's
     * `dayOverrides`. Null means "none recorded" — including on every row written before this
     * column existed — and reads back as an empty map, never as a second shape of "none".
     */
    val dayOverridesJson: String? = null
)
