package com.coparently.app.domain.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wait between attempts to re-establish a chat listener.
 *
 * Two properties matter and only one is obvious. The obvious one is that it grows. The other
 * is that it is **always positive**: a listener failing for long enough drives the attempt
 * count high, and an overflowed shift would produce a negative delay — a busy loop against a
 * backend that is already refusing every request.
 */
class ChatRetryBackoffTest {

    @Test
    fun `the first wait is a second`() {
        assertEquals(1_000L, ChatRetryBackoff.delayMillis(0))
    }

    @Test
    fun `it doubles up to the ceiling and then stays there`() {
        assertEquals(2_000L, ChatRetryBackoff.delayMillis(1))
        assertEquals(4_000L, ChatRetryBackoff.delayMillis(2))
        assertEquals(8_000L, ChatRetryBackoff.delayMillis(3))
        assertEquals(16_000L, ChatRetryBackoff.delayMillis(4))
        assertEquals(32_000L, ChatRetryBackoff.delayMillis(5))
        assertEquals(60_000L, ChatRetryBackoff.delayMillis(6))
        assertEquals(60_000L, ChatRetryBackoff.delayMillis(7))
    }

    @Test
    fun `a listener that has been failing for a day still waits, and waits sanely`() {
        // 1000L shl 64 is 1000L again, and shl 62 is negative. Either turns the backoff into
        // a busy loop, which is worse than the failure it is retrying.
        listOf(60L, 63L, 64L, 1_000L, Long.MAX_VALUE).forEach { attempt ->
            assertEquals(
                ChatRetryBackoff.CEILING_MILLIS,
                ChatRetryBackoff.delayMillis(attempt),
                "attempt $attempt did not clamp to the ceiling"
            )
        }
    }

    @Test
    fun `every wait is positive`() {
        val attempts = (0L..70L).toList() + listOf(1_000L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)

        attempts.forEach { attempt ->
            assertTrue(
                ChatRetryBackoff.delayMillis(attempt) > 0L,
                "attempt $attempt produced a non-positive delay"
            )
        }
    }
}
