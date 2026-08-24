package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What the co-parent is told a proposal would change.
 *
 * The interesting cases are the ones that used to be answered wrongly by silence: a proposal
 * that changes nothing, and a pattern the app cannot read.
 */
class CustodyPatternDiffTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun model(momDays: Set<Int>, days: Int = 14, start: LocalDate = monday) = CustodyModel(
        id = "m",
        modelType = CustodyModelType.CUSTOM,
        patternDays = days,
        momDayIndices = momDays,
        startDate = start
    )

    @Test
    fun `a swapped fortnight moves every day and nets to zero either way`() {
        val agreed = model((0..6).toSet())
        val proposed = agreed.complemented()

        val diff = CustodyPatternDiff.of(agreed, proposed, monday)

        assertTrue(diff.comparable)
        assertFalse(diff.identical)
        assertEquals(14, diff.movedDayCount)
        assertEquals(-7, diff.netDaysBySlot["mom"])
        assertEquals(7, diff.netDaysBySlot["dad"])
    }

    @Test
    fun `moving one weekend reports one day per weekend day, not the whole cycle`() {
        val agreed = model((0..13).toSet() - setOf(5, 6))
        val proposed = model((0..13).toSet() - setOf(5, 6, 12, 13))

        val diff = CustodyPatternDiff.of(agreed, proposed, monday)

        assertEquals(2, diff.movedDayCount)
        assertEquals(-2, diff.netDaysBySlot["mom"])
        assertEquals(2, diff.netDaysBySlot["dad"])
        assertEquals(setOf("mom", "dad"), diff.netDaysBySlot.keys)
    }

    @Test
    fun `a proposal that assigns the same days is identical, not an empty change list`() {
        val agreed = model((0..6).toSet())
        // Same schedule expressed a cycle later: every day still lands on the same parent.
        val proposed = model((0..6).toSet(), start = monday.plusDays(14))

        val diff = CustodyPatternDiff.of(agreed, proposed, monday)

        assertTrue(diff.comparable)
        assertTrue(diff.identical)
        assertTrue(diff.netDaysBySlot.isEmpty())
    }

    @Test
    fun `no agreed pattern is not comparable, so the screen falls back rather than guessing`() {
        val diff = CustodyPatternDiff.of(null, model((0..6).toSet()), monday)

        assertFalse(diff.comparable)
        assertFalse(diff.identical)
    }

    @Test
    fun `a cycle length off an unvalidated document is refused, never silently equal`() {
        val agreed = model((0..6).toSet())
        val nonsense = model(setOf(0), days = 0)

        val diff = CustodyPatternDiff.of(agreed, nonsense, monday)

        assertFalse("a zero-length cycle must not read as 'nothing changes'", diff.identical)
        assertFalse(diff.comparable)
    }

    @Test
    fun `the window covers the least common multiple of two different cycles`() {
        // 14 and 21 agree on the first fortnight and diverge after it; a fortnight-long window
        // would call them identical.
        val agreed = model((0..6).toSet(), days = 14)
        val proposed = model((0..13).toSet(), days = 21)

        val diff = CustodyPatternDiff.of(agreed, proposed, monday)

        assertEquals(42, diff.windowDays)
        assertFalse(diff.identical)
    }

    @Test
    fun `the window is capped so an unreadable pair cannot make the scan unbounded`() {
        val agreed = model((0..6).toSet(), days = 101)
        val proposed = model((0..6).toSet(), days = 103)

        val diff = CustodyPatternDiff.of(agreed, proposed, monday, maxWindowDays = 56)

        assertEquals(56, diff.windowDays)
    }
}
