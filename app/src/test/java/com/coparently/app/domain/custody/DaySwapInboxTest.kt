package com.coparently.app.domain.custody

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the inbox shows about one-off swaps.
 *
 * The case that earns this file is the declined one. Nothing deletes an answered swap, so
 * filtering the list on "still pending" would have bounded it — and made a refusal disappear
 * without the parent who offered it ever being told. Bounding on the date instead keeps the
 * answer visible for exactly as long as it can still matter.
 */
class DaySwapInboxTest {

    private val today = LocalDate.of(2026, 9, 1)
    private val mom = "uid-mom"
    private val dad = "uid-dad"

    private fun swap(status: DayOverrideStatus, requestedBy: String = mom) = DayOverride(
        toParent = "dad",
        requestedBy = requestedBy,
        requestedAt = "2026-08-23T10:00:00",
        status = status,
        decidedBy = dad.takeIf { status != DayOverrideStatus.PENDING },
        decidedAt = "2026-08-23T11:00:00".takeIf { status != DayOverrideStatus.PENDING }
    )

    @Test
    fun `swaps are listed soonest first`() {
        val visible = DaySwapInbox.visible(
            mapOf(
                "2026-09-20" to swap(DayOverrideStatus.PENDING),
                "2026-09-05" to swap(DayOverrideStatus.PENDING)
            ),
            today
        )

        assertEquals(listOf(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 20)), visible.map { it.date })
    }

    @Test
    fun `a declined swap is still shown, so the parent who offered it learns the answer`() {
        val visible = DaySwapInbox.visible(
            mapOf("2026-09-05" to swap(DayOverrideStatus.DECLINED)),
            today
        )

        assertEquals(1, visible.size)
        assertEquals(DayOverrideStatus.DECLINED, visible.single().override.status)
    }

    @Test
    fun `a day already lived is history, not inbox`() {
        val visible = DaySwapInbox.visible(
            mapOf("2026-08-31" to swap(DayOverrideStatus.PENDING)),
            today
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `today itself is still shown`() {
        // The boundary is "before today", not "today or before": a swap of today is the one a
        // parent most needs to see.
        val visible = DaySwapInbox.visible(
            mapOf(today.toString() to swap(DayOverrideStatus.PENDING)),
            today
        )

        assertEquals(1, visible.size)
    }

    @Test
    fun `a key that is not a date is dropped rather than guessed at`() {
        val visible = DaySwapInbox.visible(
            mapOf("whenever" to swap(DayOverrideStatus.PENDING)),
            today
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `only the parent who did not offer is asked to answer`() {
        val pending = DaySwap(LocalDate.of(2026, 9, 5), swap(DayOverrideStatus.PENDING))

        assertTrue(DaySwapInbox.awaitsAnswerFrom(pending, dad))
        assertFalse(DaySwapInbox.awaitsAnswerFrom(pending, mom))
    }

    @Test
    fun `an answered swap asks nobody`() {
        val accepted = DaySwap(LocalDate.of(2026, 9, 5), swap(DayOverrideStatus.ACCEPTED))

        assertFalse(DaySwapInbox.awaitsAnswerFrom(accepted, dad))
    }

    // ---- grouping -------------------------------------------------------------

    private fun grouped(group: String?, requestedBy: String = mom) = DayOverride(
        toParent = "dad",
        requestedBy = requestedBy,
        requestedAt = "2026-08-23T10:00:00",
        status = DayOverrideStatus.PENDING,
        groupId = group
    )

    @Test
    fun `days offered together are one group`() {
        val groups = DaySwapInbox.groups(
            mapOf(
                "2026-09-03" to grouped("g1"),
                "2026-09-02" to grouped("g1"),
                "2026-09-04" to grouped("g1")
            ),
            today
        )

        assertEquals(1, groups.size)
        assertEquals(3, groups.first().dayCount)
        assertEquals(LocalDate.of(2026, 9, 2), groups.first().firstDate)
        assertEquals(LocalDate.of(2026, 9, 4), groups.first().lastDate)
        assertEquals(listOf("2026-09-02", "2026-09-03", "2026-09-04"), groups.first().dates)
    }

    @Test
    fun `swaps with no group id stay separate, one card each`() {
        // Every swap written before groups existed carries a null id. Folding them into one
        // group would merge unrelated agreements into a single Accept button.
        val groups = DaySwapInbox.groups(
            mapOf(
                "2026-09-02" to grouped(null),
                "2026-09-05" to grouped(null)
            ),
            today
        )

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.dayCount == 1 })
    }

    @Test
    fun `groups are ordered by their first day`() {
        val groups = DaySwapInbox.groups(
            mapOf(
                "2026-09-10" to grouped("later"),
                "2026-09-11" to grouped("later"),
                "2026-09-03" to grouped("sooner")
            ),
            today
        )

        assertEquals(listOf(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 10)), groups.map { it.firstDate })
    }

    @Test
    fun `a group awaits the parent who did not offer it`() {
        val groups = DaySwapInbox.groups(
            mapOf(
                "2026-09-02" to grouped("g1", requestedBy = mom),
                "2026-09-03" to grouped("g1", requestedBy = mom)
            ),
            today
        )

        assertTrue(groups.single().awaitsAnswerFrom(dad))
        assertFalse(groups.single().awaitsAnswerFrom(mom))
    }

    @Test
    fun `a group whose days have already started is bounded by today, like the flat list`() {
        val groups = DaySwapInbox.groups(
            mapOf(
                "2026-08-30" to grouped("g1"),
                "2026-09-02" to grouped("g1")
            ),
            today
        )

        assertEquals(1, groups.single().dayCount)
        assertEquals(LocalDate.of(2026, 9, 2), groups.single().firstDate)
    }
}
