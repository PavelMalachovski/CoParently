package com.coparently.app.presentation.pairing

import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The decision behind the pairing conflict screen.
 *
 * Every model here carries an explicit [id]. Two `CustodyModel`s built from the same fields are
 * equal `data class` instances, so a test that let both sides share an id would pass whichever
 * side `resolve` returned — pinning nothing at all. The ids are what make these assertions
 * discriminate.
 */
class CustodyConflictResolverTest {

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
    fun `equivalent patterns are not a conflict`() {
        // Same arrangement, anchored a whole cycle apart. Compared field by field these differ
        // in startDate; compared by outcome they assign every day identically, which is the
        // only comparison that means anything to a parent.
        val mine = model(14, (0..6).toSet(), id = "mine")
        val theirs = model(14, (0..6).toSet(), start = LocalDate.of(2026, 8, 17), id = "theirs")

        assertEquals(
            CustodyConflict.NoConflict(theirs),
            CustodyConflictResolver.resolve(mine, theirs)
        )
    }

    @Test
    fun `no local pattern means take theirs without asking`() {
        val theirs = model(14, (0..6).toSet(), id = "theirs")

        assertEquals(
            CustodyConflict.NoLocal(theirs),
            CustodyConflictResolver.resolve(null, theirs)
        )
    }

    @Test
    fun `no remote pattern means keep mine without asking`() {
        val mine = model(14, (0..6).toSet(), id = "mine")

        assertEquals(
            CustodyConflict.NoConflict(mine),
            CustodyConflictResolver.resolve(mine, null)
        )
    }

    @Test
    fun `neither pattern present is not a conflict`() {
        assertEquals(
            CustodyConflict.NoConflict(null),
            CustodyConflictResolver.resolve(null, null)
        )
    }

    @Test
    fun `different patterns are a conflict`() {
        val mine = model(14, (0..6).toSet(), id = "mine")
        val theirs = model(14, setOf(0, 1, 4, 5, 6, 9, 10), id = "theirs")

        assertEquals(
            CustodyConflict.Conflict(mine, theirs),
            CustodyConflictResolver.resolve(mine, theirs)
        )
    }

    @Test
    fun `my pattern is complemented before it is compared`() {
        // I was slot 1 with days 0-6. After pairing I am slot 2, so "my days" are 7-13.
        // The co-parent, in slot 1, has days 7-13 — describing the same arrangement.
        // Comparing without complementing calls this a conflict and offers me my own
        // schedule inverted; I reject it and hand over exactly the days I meant to keep.
        val mineBeforeFlip = model(14, (0..6).toSet(), id = "mine")
        val theirs = model(14, (7..13).toSet(), id = "theirs")

        assertEquals(
            CustodyConflict.NoConflict(theirs),
            CustodyConflictResolver.resolve(mineBeforeFlip.complemented(), theirs)
        )
    }

    @Test
    fun `an un-complemented pattern is the conflict this screen exists to prevent`() {
        // The same two models as above, compared without the complement — the mistake stated in
        // one assertion rather than only in a comment. If resolve ever started complementing
        // internally, or a caller stopped complementing, exactly one of these two tests fails.
        val mineBeforeFlip = model(14, (0..6).toSet(), id = "mine")
        val theirs = model(14, (7..13).toSet(), id = "theirs")

        assertEquals(
            CustodyConflict.Conflict(mineBeforeFlip, theirs),
            CustodyConflictResolver.resolve(mineBeforeFlip, theirs)
        )
    }

    @Test
    fun `a settled outcome names the pattern to write, a conflict names none`() {
        val mine = model(14, (0..6).toSet(), id = "mine")
        val theirs = model(14, setOf(0, 1, 4, 5, 6, 9, 10), id = "theirs")

        assertEquals(theirs, CustodyConflict.NoLocal(theirs).settled)
        assertEquals(mine, CustodyConflict.NoConflict(mine).settled)
        assertEquals(null, CustodyConflict.NoConflict(null).settled)
        assertEquals(null, CustodyConflict.Conflict(mine, theirs).settled)
    }
}
