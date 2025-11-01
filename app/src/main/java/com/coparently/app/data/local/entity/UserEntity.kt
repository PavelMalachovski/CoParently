package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a user (parent) in the local Room database.
 *
 * @property id Unique identifier for the user
 * @property email Email address of the user
 * @property name Display name of the user
 * @property role Role of the user ("mom" or "dad")
 * @property colorCode Color code for displaying user's events in the calendar
 * @property profilePhotoUrl Optional URL for profile photo
 * @property googleCalendarSyncEnabled Whether Google Calendar sync is enabled
 * @property googleCalendarId Optional ID of the Google Calendar for sync
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val email: String,
    val name: String,
    val role: String, // "mom" or "dad"
    val colorCode: String, // Hex color code (e.g., "#FF4081" for pink, "#2196F3" for blue)
    val profilePhotoUrl: String? = null,
    val googleCalendarSyncEnabled: Boolean = false,
    val googleCalendarId: String? = null
)

