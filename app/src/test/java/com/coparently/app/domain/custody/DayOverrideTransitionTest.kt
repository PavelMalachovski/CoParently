package com.coparently.app.domain.custody

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules behind offering one day to the other parent.
 *
 * The property that matters most is that a parent cannot decide their own offer. Without it a
 * swap is not an agreement at all — either parent could grant themselves a day and the other
 * would merely be told, which is exactly the state PR #47 existed to end for whole patterns.
 */
class DayOverrideTransitionTest {

    private val mom = "uid-mom"
    private val dad = "uid-dad"
    private val date = "2026-09-05"
    private val now = "2026-08-23T10:00:00"

    @Test
    fun `offering records a pending override for that date`() {
        val next = DayOverrideTransition
            .offer(emptyMap(), date, toParent = "dad", byUid = mom, atIso = now).getOrThrow()

        assertEquals(DayOverrideStatus.PENDING, next.getValue(date).status)
        assertEquals("dad", next.getValue(date).toParent)
        assertEquals(mom, next.getValue(date).requestedBy)
    }

    @Test
    fun `a second offer for the same date replaces the first rather than queueing`() {
        val first = DayOverrideTransition
            .offer(emptyMap(), date, "dad", mom, now).getOrThrow()
        val second = DayOverrideTransition
            .offer(first, date, "mom", dad, "2026-08-23T11:00:00").getOrThrow()

        assertEquals(1, second.size)
        assertEquals(dad, second.getValue(date).requestedBy)
    }

    @Test
    fun `the parent who offered cannot accept their own offer`() {
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()

        assertTrue(DayOverrideTransition.accept(offered, date, byUid = mom, atIso = now).isFailure)
    }

    @Test
    fun `the other parent can accept it`() {
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()
        val accepted = DayOverrideTransition.accept(offered, date, dad, now).getOrThrow()

        assertEquals(DayOverrideStatus.ACCEPTED, accepted.getValue(date).status)
        assertEquals(dad, accepted.getValue(date).decidedBy)
    }

    @Test
    fun `a decided override cannot be decided again`() {
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()
        val accepted = DayOverrideTransition.accept(offered, date, dad, now).getOrThrow()

        assertTrue(DayOverrideTransition.decline(accepted, date, dad, now).isFailure)
    }

    @Test
    fun `deciding a date with no override fails rather than inventing one`() {
        assertTrue(DayOverrideTransition.accept(emptyMap(), date, dad, now).isFailure)
    }

    @Test
    fun `awaitsAnswerFrom is true only for the other parent's still-pending offer`() {
        // The predicate a caller writing a group one day at a time has to apply itself, against
        // the map the write just read. Getting it from a Room mirror instead is what made a
        // part-answered group unfinishable: the mirror can only be behind, and it is behind
        // exactly on the retry after a partial run.
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()

        assertTrue(DayOverrideTransition.awaitsAnswerFrom(offered, date, dad))
        assertFalse(DayOverrideTransition.awaitsAnswerFrom(offered, date, mom), "your own offer")
        assertFalse(DayOverrideTransition.awaitsAnswerFrom(offered, "2026-09-12", dad), "no entry")

        val answered = DayOverrideTransition.accept(offered, date, dad, now).getOrThrow()
        assertFalse(DayOverrideTransition.awaitsAnswerFrom(answered, date, dad), "already decided")
    }

    @Test
    fun `an offer for one date leaves every other date alone`() {
        val other = "2026-09-12"
        val first = DayOverrideTransition.offer(emptyMap(), other, "mom", dad, now).getOrThrow()
        val second = DayOverrideTransition.offer(first, date, "dad", mom, now).getOrThrow()

        assertEquals(setOf(other, date), second.keys)
        assertEquals(DayOverrideStatus.PENDING, second.getValue(other).status)
    }
}
