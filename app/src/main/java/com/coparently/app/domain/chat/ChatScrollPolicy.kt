package com.coparently.app.domain.chat

/**
 * Where the message list should be scrolled to, given what it is currently showing.
 *
 * Kept out of the composable so the decision can be tested at all: the project has JVM unit
 * tests only. The August 2026 baseline found a thread of any real length opening on its
 * *oldest* message, because the list had no initial scroll and its only rule — "follow the
 * newest message if you are already near the bottom" — is false on first composition, where
 * both visible indices are still 0.
 */
object ChatScrollPolicy {

    /** How close to the end counts as "the user is reading the newest messages". */
    private const val NEAR_BOTTOM_SLACK = 2

    /**
     * @param entryCount Rendered entries — messages *and* day separators.
     * @param firstVisibleIndex First entry currently on screen.
     * @param lastVisibleIndex Last entry currently on screen; 0 before the first layout pass.
     * @param initialJumpDone Whether the one-shot jump to the newest message already happened.
     * @return Index to scroll to, or null to leave the list where it is.
     */
    fun targetIndex(
        entryCount: Int,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        initialJumpDone: Boolean
    ): Int? {
        if (entryCount == 0) return null
        val last = entryCount - 1
        val nearBottom = lastVisibleIndex >= last - NEAR_BOTTOM_SLACK ||
            firstVisibleIndex >= last - NEAR_BOTTOM_SLACK - 1

        // Opening the thread: always land on the newest message, whatever the length.
        // Afterwards, only follow new arrivals when the reader is already at the end —
        // yanking someone out of history they are reading is worse than a missed message.
        return when {
            !initialJumpDone -> last
            lastVisibleIndex >= last -> null
            nearBottom -> last
            else -> null
        }
    }
}
