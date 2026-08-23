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
     * UID.
     *
     * **`EncryptedPreferences.clear()` deliberately exempts every key under this prefix** — see
     * its KDoc. That exemption is exactly why the per-UID scoping here is load-bearing rather
     * than defensive-only: a marker that now survives sign-out would otherwise be read by a
     * second account that later signs in on the same device as if it were that account's own
     * history. (Room's `users`/`events` rows already survive sign-out the same way, unscoped —
     * they are matched by uid at the query level instead; see `CustodyModelRepository`'s own
     * doc for that precedent.)
     *
     * This is deliberately **not** `User.role`: that field is a placeholder on profile creation
     * (`UserRepositoryImpl.DEFAULT_ROLE`) whenever the first profile read fails or has not
     * landed yet, and the accept path (`PairingViewModel.withSlotReslot`) changes which slot a
     * device is in without ever writing it — see `ParentSlotMigrator.reslotIfSlotChanged` for
     * why a value that can lag or be a guess cannot be the "before" side of a change detector.
     */
    const val PARENT_SLOT_MARKER_PREFIX = "parent_slot_marker_"

    /**
     * Prefix for the per-user key recording which co-parent this device has already re-published
     * its own events for — the actual key is this prefix plus the Firebase UID, and the value is
     * the partner's UID.
     *
     * Scoped per user for the same reason [PARENT_SLOT_MARKER_PREFIX] is: Room's `users` and
     * `events` rows survive sign-out, so a second account signing in on the same device must not
     * read the first account's history as its own.
     *
     * Unlike [PARENT_SLOT_MARKER_PREFIX] this key is **not** exempt from
     * `EncryptedPreferences.clear()`, and does not need to be. Losing it costs one extra
     * re-publish of documents that already carry the right audience; losing the slot marker
     * would re-stamp records into the wrong parent's slot.
     */
    const val EVENT_AUDIENCE_BACKFILL_PREFIX = "event_audience_backfill_"

    /**
     * Prefix for the per-user key recording which co-parent this device has already re-published
     * its own **child info** for — the actual key is this prefix plus the Firebase UID, and the
     * value is the partner's UID.
     *
     * Separate from [EVENT_AUDIENCE_BACKFILL_PREFIX] rather than shared with it: the two backfills
     * were introduced at different times, so on an install that already ran the events one a
     * shared key would read as "child info is done too" and skip it forever.
     *
     * The value is the partner's UID and never a boolean, for the reason spelled out on
     * [EVENT_AUDIENCE_BACKFILL_PREFIX]: a boolean never re-arms when the same two people pair
     * again.
     */
    const val CHILD_INFO_AUDIENCE_BACKFILL_PREFIX = "child_info_audience_backfill_"
}
