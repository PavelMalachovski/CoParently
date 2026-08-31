package com.coparently.app.data.sync

/**
 * Whether the next events download reads everything or only what changed (CQ-5).
 *
 * Kept out of `SyncService` and away from Firestore so the rule can be unit tested. It is three
 * lines of arithmetic guarding the correctness of every other line in the download, which is
 * exactly the shape of thing that is never tested and always wrong.
 *
 * **The sweep is not belt-and-braces; it is what makes the delta safe.** A document written
 * before `serverUpdatedAt` existed has no such field, and Firestore excludes a document that
 * lacks a field from a `whereGreaterThan` outright rather than treating it as zero — the same
 * silent exclusion that dropped pre-fix `budgets` from their `whereIn` and left them invisible
 * with no error. A delta alone would therefore never deliver a single event created before this
 * shipped. The sweep collects them within a day, and their first edit gives them the field for
 * good, so the fix needs no backfill and no ops step.
 *
 * It also covers the failures nobody enumerated: a cursor persisted from a half-finished pass, a
 * document whose `sharedWith` changed by a path that skipped the stamp, a clock the server itself
 * corrected. **A sweep only ever adds and updates — it never reconciles by absence**, so it
 * cannot delete anything, which is the property CLAUDE.md item 14 protects.
 */
object EventSyncWindow {

    /**
     * How long a delta may run before a full sweep is due.
     *
     * A day, not an hour: the point of the sweep is to bound how long a document can stay missed,
     * and the cost of shortening it is paid on every user's bill. `SyncWorker` runs every fifteen
     * minutes, so this turns 96 full collection reads a day into one, and 95 deltas that on a
     * quiet day return nothing.
     */
    const val SWEEP_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * True when this pass must read the whole collection.
     *
     * @param cursorMillis The highest change cursor this device has seen for this account, or
     *   null when it has never completed a pass.
     * @param lastSweepAtMillis When the last full pass finished, or null when there has not been
     *   one.
     * @param nowMillis Now.
     */
    fun needsFullSweep(
        cursorMillis: Long?,
        lastSweepAtMillis: Long?,
        nowMillis: Long
    ): Boolean {
        if (cursorMillis == null || lastSweepAtMillis == null) return true
        // A last-sweep stamp in the future is a clock that moved backwards — a reinstall from a
        // backup, or a user correcting the date. Sweeping is the safe reading: it costs one
        // download and repairs whatever the bad clock let through, where trusting it would park
        // the device on a delta whose cursor may be hours ahead of reality.
        if (lastSweepAtMillis > nowMillis) return true
        return nowMillis - lastSweepAtMillis >= SWEEP_INTERVAL_MILLIS
    }
}
