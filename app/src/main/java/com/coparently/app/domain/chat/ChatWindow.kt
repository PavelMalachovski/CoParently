package com.coparently.app.domain.chat

/**
 * How much of a thread the chat screen holds in memory, and when there is more (CQ-6).
 *
 * `MessageDao.getMessages` had no bound, so opening Chat materialised every message a pair had
 * ever exchanged out of Room — the last unbounded query in the feature, after the home badge
 * became a `COUNT(*)` and the Firestore listener was capped at 200.
 *
 * Kept out of the ViewModel and out of Compose so it can be tested at all: the project has JVM
 * unit tests only, and every rule here is the kind that is obvious until it is off by one.
 */
object ChatWindow {

    /**
     * Messages loaded when a thread opens.
     *
     * Comfortably more than a phone screen holds, so the common case — open, read the last few,
     * reply — never touches the button. Deliberately **below** the Firestore listener's 200: the
     * remote window is what the device is willing to *receive*, this is what one screen is
     * willing to *render*, and making them equal would tie two unrelated decisions together.
     */
    const val INITIAL: Int = 50

    /** How many more each "load earlier" adds. Same as [INITIAL]: one more screenful of history. */
    const val STEP: Int = 50

    /**
     * The next window after a "load earlier".
     *
     * **It grows; it does not page.** A window that slid would make the messages the reader is
     * looking at disappear off the bottom — they asked for more, not for different — and it would
     * put the list into a state where scrolling down loads forward again, which is a scroll
     * position nobody can hold onto. Growing costs the memory of the messages actually asked for,
     * and buys a list that only ever gets longer at one end.
     *
     * @param current The window in force now.
     */
    fun grow(current: Int): Int = current + STEP

    /**
     * Whether there may be older messages than the ones loaded.
     *
     * `loaded >= limit` rather than `>`: a full window is the only evidence available without a
     * second query, and asking Room for a count on every emission to answer a button's visibility
     * would reintroduce a cost this item exists to remove.
     *
     * The honest cost of that is one case: a thread of *exactly* [INITIAL] messages offers the
     * button once, and it disappears after a grow that finds nothing. A button that does nothing
     * once beats a thread whose history is silently unreachable — which is the CQ-7 defect, in a
     * different collection.
     *
     * @param loaded How many messages came back.
     * @param limit What was asked for.
     */
    fun hasMore(loaded: Int, limit: Int): Boolean = loaded >= limit
}
