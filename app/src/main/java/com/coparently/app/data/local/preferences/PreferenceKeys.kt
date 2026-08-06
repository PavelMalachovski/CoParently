package com.coparently.app.data.local.preferences

/**
 * Shared preference keys used across features.
 * Kept in one place so different ViewModels read/write the same entries.
 */
object PreferenceKeys {
    /** Pipe-separated set of event types hidden in the calendar. */
    const val HIDDEN_EVENT_TYPES = "calendar_hidden_event_types"

    /** Pipe-separated list of user-defined event types. */
    const val CUSTOM_EVENT_TYPES = "calendar_custom_event_types"

    /** Whether Czech holidays and school vacations are shown in the calendar. */
    const val SHOW_HOLIDAYS = "calendar_show_holidays"

    /**
     * ISO date-time of the last shared-custody change the user dismissed the "schedule changed"
     * banner for. Keyed on `SharedCustody.lastModifiedAt` rather than held in ViewModel state, so
     * a change the user already acknowledged does not reappear just because they killed the app,
     * while the next change — a different `lastModifiedAt` — is announced again.
     */
    const val DISMISSED_CUSTODY_CHANGE_AT = "calendar_dismissed_custody_change_at"

    /** Separator for multi-value string preferences. */
    const val LIST_SEPARATOR = "|"

    /**
     * Prefix for the per-user key that records the parent slot (`"mom"`/`"dad"`) this device's
     * own records are currently stamped with — the actual key is this prefix plus the Firebase
     * UID, so a device where two accounts have signed in over time (Room's `users` rows are
     * never cleared on sign-out either, see `CustodyModelRepository`) does not read one
     * account's marker as another's.
     *
     * This is deliberately **not** `User.role`: that field is a placeholder on profile creation
     * (`UserRepositoryImpl.DEFAULT_ROLE`) whenever the first profile read fails or has not
     * landed yet, and the accept path (`PairingViewModel.withSlotReslot`) changes which slot a
     * device is in without ever writing it — see `ParentSlotMigrator.reslotIfSlotChanged` for
     * why a value that can lag or be a guess cannot be the "before" side of a change detector.
     */
    const val PARENT_SLOT_MARKER_PREFIX = "parent_slot_marker_"
}
