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
 * @property lastModifiedAt ISO date-time string when this model was last modified
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
    val lastModifiedAt: String,
    /**
     * JSON object of one-off day swaps keyed by ISO date, mirroring the shared document's
     * `dayOverrides`. Null means "none recorded" — including on every row written before this
     * column existed — and reads back as an empty map, never as a second shape of "none".
     */
    val dayOverridesJson: String? = null
)
