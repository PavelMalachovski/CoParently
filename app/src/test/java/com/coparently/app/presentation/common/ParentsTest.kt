package com.coparently.app.presentation.common

import com.coparently.app.domain.expenses.calculateExpenseBalance
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Parents.roleByUid] is what `ExpenseBalance.splitKnown` is decided from, so these cases are
 * really about when the expense split block is allowed to appear at all.
 */
class ParentsTest {

    private val me = NamedParent(uid = "u1", slot = "mom", name = "Olya")
    private val coParent = NamedParent(uid = "u2", slot = "dad", name = "Pavel")

    @Test
    fun `two properly slotted parents map both uids`() {
        assertEquals(
            mapOf("u1" to "mom", "u2" to "dad"),
            Parents(me, coParent).roleByUid
        )
    }

    @Test
    fun `an unknown parent contributes no entry`() {
        assertEquals(mapOf("u1" to "mom"), Parents(me = me, coParent = null).roleByUid)
        assertEquals(mapOf("u2" to "dad"), Parents(me = null, coParent = coParent).roleByUid)
        assertEquals(emptyMap(), Parents().roleByUid)
    }

    @Test
    fun `both parents known makes the expense split meaningful`() {
        // The split block in ExpenseSummaryHeader is gated on this. Before the co-parent's slot
        // could be read at all, roleByUid came from Room - which stores the signed-in user
        // alone - so this was false on every device and the block never rendered.
        val balance = calculateExpenseBalance(
            expenses = emptyList(),
            currentUserId = "u1",
            roleByUid = Parents(me, coParent).roleByUid
        )
        assertTrue(balance.splitKnown)
    }

    @Test
    fun `two parents sharing a slot leave the split unknown`() {
        // A pair created before slot assignment shipped. Both read "mom", so there is no second
        // slot to attribute anything to and the split bar stays hidden until the backfill
        // separates them. Deliberately not papered over with a fallback.
        val balance = calculateExpenseBalance(
            expenses = emptyList(),
            currentUserId = "u1",
            roleByUid = Parents(me, coParent.copy(slot = "mom")).roleByUid
        )
        assertFalse(balance.splitKnown)
    }

    @Test
    fun `an unpaired parent leaves the split unknown`() {
        val balance = calculateExpenseBalance(
            expenses = emptyList(),
            currentUserId = "u1",
            roleByUid = Parents(me = me, coParent = null).roleByUid
        )
        assertFalse(balance.splitKnown)
    }
}
