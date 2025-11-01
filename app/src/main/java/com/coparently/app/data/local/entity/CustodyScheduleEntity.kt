package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a custody schedule pattern in the local Room database.
 *
 * @property id Unique identifier for the custody schedule
 * @property parentOwner Parent who has custody ("mom" or "dad")
 * @property dayOfWeek Day of the week (1 = Monday, 7 = Sunday)
 * @property isActive Whether this schedule pattern is currently active
 * @property startDate Optional start date for the schedule
 * @property endDate Optional end date for the schedule
 */
@Entity(tableName = "custody_schedules")
data class CustodyScheduleEntity(
    @PrimaryKey
    val id: String,
    val parentOwner: String, // "mom" or "dad"
    val dayOfWeek: Int, // 1 = Monday, 7 = Sunday
    val isActive: Boolean = true,
    val startDate: String? = null, // ISO date string
    val endDate: String? = null // ISO date string
)

