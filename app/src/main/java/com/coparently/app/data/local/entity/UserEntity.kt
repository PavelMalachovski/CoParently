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
 * @property partnerId Optional ID of the co-parent partner (Firebase UID)
 * @property fcmToken Firebase Cloud Messaging token for push notifications
 * @property dateOfBirth ISO `LocalDate` string, e.g. `1988-04-17`; null until the parent records it
 * @property phone Free-text phone number as the parent typed it; no format is imposed
 * @property allergiesJson JSON array of allergy strings; `[]` when none
 * @property medicalProfileJson JSON object of [com.coparently.app.domain.model.MedicalProfile];
 * `{}` when never filled
 * @property onboardingCompletedAt ISO date-time at which this user finished (or skipped
 * through) first-run onboarding; null while the wizard has not been completed
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
    val googleCalendarId: String? = null,
    val partnerId: String? = null,
    /**
     * Every co-parent this account has, as a JSON array of Firebase UIDs.
     *
     * A person may co-parent with more than one other adult, and [partnerId] holds one — so it
     * stopped being the answer to "who are my co-parents" and became the answer to **"which
     * family is this device showing"** (see `SelectedFamilySource`). The distinction is why
     * both fields exist: this one is the account's real state, mirrored from
     * `users/{uid}.partnerIds`, while [partnerId] is a per-device view of it.
     *
     * A JSON array of plain strings and never a Gson-serialised type, for the reason
     * `FamilyMemberRef` records: R8 rewrote a Gson model's field names once already and it
     * shipped.
     */
    val partnerIdsJson: String = "[]",
    val fcmToken: String? = null,
    /** ISO `LocalDate` string, e.g. `1988-04-17`. Null until the parent records it. */
    val dateOfBirth: String? = null,
    /** Free-text phone number as the parent typed it; no format is imposed. */
    val phone: String? = null,
    /** JSON array of allergy strings; `[]` when none. Mirrors `ChildInfoEntity.allergiesJson`. */
    val allergiesJson: String = "[]",
    /** JSON object of [com.coparently.app.domain.model.MedicalProfile]; `{}` when never filled. */
    val medicalProfileJson: String = "{}",
    /**
     * ISO date-time at which this user finished (or skipped through) first-run onboarding.
     * Null means the wizard has not been completed. A string rather than a converted type
     * because that is how every date crosses this Firestore schema.
     */
    val onboardingCompletedAt: String? = null,
    /**
     * `FamilyKind` constant names joined by a pipe, or null while the question is unanswered.
     *
     * Names rather than a localized label, and null rather than `""` for "none": a blank and a
     * null must not be two spellings of the same value in a column a whole-row comparison reads.
     */
    val caresForKinds: String? = null,
    /**
     * ISO 3166-1 alpha-2 country, deciding which holiday calendar the grid draws.
     *
     * `NOT NULL DEFAULT 'CZ'` in SQLite, which is what stamps every pre-existing row as Czechia
     * — see [com.coparently.app.domain.model.User.countryCode].
     */
    val countryCode: String = "CZ"
)

