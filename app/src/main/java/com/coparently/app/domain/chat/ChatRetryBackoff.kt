package com.coparently.app.domain.chat

/**
 * How long to wait before re-establishing a failed chat listener.
 *
 * Pure, and its own file, because it is the only part of the retry with arithmetic that can be
 * wrong — a shift that overflows, a ceiling that never applies — and the surrounding code is a
 * Firestore listener that no unit test in this project can reach.
 *
 * The shape is exponential with a ceiling and **no attempt limit**. A limit is the wrong idea
 * here: what it would buy is a final `catch`, and the state that leaves behind is the one this
 * exists to prevent — `merge(mirror, local)` running on Room alone for the rest of the process,
 * with the app looking entirely healthy while receiving nothing. A capped delay costs one
 * request a minute against a backend that is refusing them anyway.
 */
object ChatRetryBackoff {

    /** The first wait, in milliseconds. */
    const val BASE_MILLIS = 1_000L

    /** The longest wait, in milliseconds. Reached at the sixth attempt. */
    const val CEILING_MILLIS = 60_000L

    /**
     * The wait before retry number [attempt], counting from 0 for the first failure.
     *
     * Doubles until it reaches [CEILING_MILLIS] and stays there. The shift is bounded before
     * it is applied rather than after: a listener that has been failing for a day reaches an
     * attempt count where `1000L shl attempt` is negative, and a negative delay would turn the
     * backoff into a busy loop against a backend that is already refusing.
     */
    fun delayMillis(attempt: Long): Long {
        if (attempt <= 0L) return BASE_MILLIS
        val shift = attempt.coerceAtMost(MAX_SHIFT).toInt()
        return (BASE_MILLIS shl shift).coerceAtMost(CEILING_MILLIS)
    }

    /**
     * The largest shift worth applying: `BASE_MILLIS shl 6` is already past [CEILING_MILLIS],
     * so anything beyond it is clamped to the same answer by a cheaper route.
     */
    private const val MAX_SHIFT = 6L
}
