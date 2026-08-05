package com.coparently.app.domain.model

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class CustodyModelTest {

    private fun model(
        patternDays: Int,
        momDays: Set<Int>,
        start: LocalDate = LocalDate.of(2026, 8, 3),
        id: String = "m"
    ) = CustodyModel(
        id = id,
        modelType = CustodyModelType.CUSTOM,
        patternDays = patternDays,
        momDayIndices = momDays,
        startDate = start
    )

    @Test
    fun `complemented swaps which days belong to which slot`() {
        val original = model(4, setOf(0, 1))
        assertEquals(setOf(2, 3), original.complemented().momDayIndices)
    }

    @Test
    fun `complementing twice returns the original`() {
        val original = model(14, setOf(0, 1, 4, 5, 6, 9, 10))
        assertEquals(original.momDayIndices, original.complemented().complemented().momDayIndices)
    }

    @Test
    fun `complementing a full set yields an empty one and back`() {
        val everyDay = model(7, (0..6).toSet())
        assertEquals(emptySet(), everyDay.complemented().momDayIndices)
        assertEquals((0..6).toSet(), everyDay.complemented().complemented().momDayIndices)
    }

    @Test
    fun `a model is equivalent to itself`() {
        val m = model(14, setOf(0, 1, 2, 3, 4, 5, 6))
        assert(m.isEquivalentTo(m))
    }

    @Test
    fun `a start date shifted by a whole cycle describes the same schedule`() {
        val a = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 3))
        val b = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 17))
        assert(a.isEquivalentTo(b)) { "14 days later in a 14-day cycle is the same pattern" }
    }

    @Test
    fun `a start date shifted by part of a cycle describes a different schedule`() {
        val a = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 3))
        val b = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 10))
        assert(!a.isEquivalentTo(b))
    }

    @Test
    fun `a complemented model is not equivalent to the original`() {
        val a = model(14, setOf(0, 1, 2, 3, 4, 5, 6))
        assert(!a.isEquivalentTo(a.complemented()))
    }

    @Test
    fun `cycles of different lengths are compared over their least common multiple`() {
        // A 14-day and a 21-day pattern can agree for the first 14 days and diverge after.
        // Comparing over a fixed window would call these equivalent.
        val fortnight = model(14, (0..6).toSet(), LocalDate.of(2026, 8, 3))
        val threeWeeks = model(21, (0..6).toSet(), LocalDate.of(2026, 8, 3))
        assert(!fortnight.isEquivalentTo(threeWeeks))
    }
}
