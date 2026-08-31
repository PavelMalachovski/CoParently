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
     * banner for, as decimal epoch millis. Keyed on `SharedCustody.lastModifiedAtMillis` rather
     * than held in ViewModel state, so
     * a change the user already acknowledged does not reappear just because they killed the app,
     * while the next change — a different instant — is announced again. A value written before
     * schema 29 is an ISO date-time and does not parse, which reads as "nothing dismissed": the
     * current change is announced once more and then re-dismissed in the new form.
     */
    const val DISMISSED_CUSTODY_CHANGE_AT = "calendar_dismissed_custody_change_at"

    /**
     * The user's answer to the analytics and crash-reporting question, as a
     * [com.coparently.app.domain.telemetry.TelemetryConsent] name.
     *
     * **Deliberately not exempt from `EncryptedPreferences.clear()`.** Sign-out wipes it, and the
     * next person to reach this device is asked again — which is the right answer for a consent,
     * because consent is given by a person and this store cannot tell which person is holding the
     * phone. The cost of the wipe is one extra screen; the value stored is "unanswered", which
     * collects nothing, so nothing leaks in the gap.
     */
    const val TELEMETRY_CONSENT = "telemetry_consent"

    /** Separator for multi-value string preferences. */
    const val LIST_SEPARATOR = "|"

    /**
     * Prefix for an unsent chat message, keyed by conversation id.
     *
     * Per conversation rather than one global slot: the composer text belongs to the thread it
     * was typed into, and a single key would carry a half-written message from one co-parent
     * into another thread. Cleared once the message is actually sent.
     *
     * Persisted rather than held in the ViewModel because the Chat tab's back-stack entry — and
     * with it the ViewModel — is cleared when the user switches tabs. That is exactly the case
     * the draft has to survive.
     */
    const val CHAT_DRAFT_PREFIX = "chat_draft_"

    /**
     * The agreed split of a shared expense, as slot 1's share in basis points.
     *
     * Cached locally rather than read from Firestore on the save path: an expense must be
     * recordable offline, and blocking a save on a document read would fail it for a reason that
     * has nothing to do with the expense. The pair's document remains the record; this is what
     * the balance math and the save path read.
     */
    const val SPLIT_RATIO_BASIS_POINTS = "expenses_split_ratio_basis_points"

    /**
     * The slot [SPLIT_RATIO_BASIS_POINTS] was captured under.
     *
     * The stored share is **slot 1's**, which is the schema; what a parent sets before pairing is
     * *their own*. An unpaired account defaults to slot 1, and pairing can move it to slot 2 —
     * so without knowing which slot the number was written under, publishing it to the pair can
     * hand the co-parent the share this parent meant to take.
     */
    const val SPLIT_RATIO_SLOT = "expenses_split_ratio_slot"

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

    /**
     * Prefix for the per-user key recording which co-parent this device has already stamped
     * `familyId` for — the actual key is this prefix plus the Firebase UID, and the value is the
     * partner's UID.
     *
     * A third key rather than a reuse of either audience marker, for the reason
     * [CHILD_INFO_AUDIENCE_BACKFILL_PREFIX] gives: an install that already ran one of those would
     * read a shared key as "done" and never stamp anything.
     *
     * The partner's UID and never a boolean — same rule again. Losing this key to
     * `EncryptedPreferences.clear()` costs one `UPDATE ... WHERE familyId IS NULL` per table that
     * matches no rows, so it does not need the slot marker's exemption.
     */
    const val FAMILY_ID_BACKFILL_PREFIX = "family_id_backfill_"

    /**
     * Prefix for the per-user key naming the family this device is currently showing — the
     * actual key is this prefix plus the Firebase UID, and the value is a `FamilyKey` id.
     *
     * Per device and never synced: which family a parent is looking at is a view, not a fact
     * about the relationship, and their co-parent has no business seeing it. Per user for the
     * reason [PARENT_SLOT_MARKER_PREFIX] gives — Room rows survive sign-out, so a second
     * account must not inherit the first one's choice.
     *
     * A stored id is validated on every read rather than trusted: it survives an unpair, and a
     * family the account has left must not select itself.
     */
    const val SELECTED_FAMILY_PREFIX = "selected_family_"
}
