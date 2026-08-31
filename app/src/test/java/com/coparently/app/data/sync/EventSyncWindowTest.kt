package com.coparently.app.data.sync

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the events download reads everything rather than the delta (CQ-5).
 *
 * The delta is the optimisation and the sweep is the correctness argument, so these tests are
 * about the sweep. Every case below is one where skipping it loses a parent's events silently —
 * no error, no crash, an event simply never arriving on the other phone.
 */
class EventSyncWindowTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `a device that has never synced reads everything`() {
        assertTrue(EventSyncWindow.needsFullSweep(null, null, now))
    }

    @Test
    fun `a cursor with no recorded sweep still reads everything`() {
        // The pair can only be trusted together. A cursor without a sweep is a half-written
        // state — a pass that advanced the cursor and then failed, or a preferences store
        // cleared in part — and continuing from it would skip whatever the missing sweep would
        // have collected.
        assertTrue(EventSyncWindow.needsFullSweep(cursorMillis = 500L, lastSweepAtMillis = null, nowMillis = now))
    }

    @Test
    fun `a recent sweep runs the delta`() {
        assertFalse(EventSyncWindow.needsFullSweep(500L, now - hour, now))
        assertFalse(EventSyncWindow.needsFullSweep(500L, now - 23 * hour, now))
    }

    @Test
    fun `a day since the last sweep is due, and the boundary counts as due`() {
        assertTrue(EventSyncWindow.needsFullSweep(500L, now - 24 * hour, now))
        assertTrue(EventSyncWindow.needsFullSweep(500L, now - 25 * hour, now))
    }

    @Test
    fun `a sweep stamped in the future sweeps rather than trusting the clock`() {
        // A restore from backup, or a user correcting the date. Trusting the stamp would park the
        // device on a delta whose cursor is ahead of anything the server will produce for hours,
        // during which nothing at all arrives.
        assertTrue(EventSyncWindow.needsFullSweep(500L, now + hour, now))
    }

    @Test
    fun `the interval is a day, and the constant is what the tests measure against`() {
        // Pinned so that changing the interval is a deliberate act with a failing test, not a
        // number quietly edited: shortening it multiplies every user's read bill, and lengthening
        // it extends how long a pre-`serverUpdatedAt` document can stay invisible.
        assertTrue(EventSyncWindow.SWEEP_INTERVAL_MILLIS == 24 * hour)
    }
}
