package com.coparently.app.domain.expenses

import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the expenses split/settle-up maths behind the Expenses header.
 */
class ExpenseBalanceTest {

    private val mom = "uid-mom"
    private val dad = "uid-dad"
    private val roles = mapOf(mom to "mom", dad to "dad")

    private fun expense(
        id: String,
        amount: Double,
        paidBy: String,
        splitBetween: List<String> = listOf(mom, dad)
    ) = Expense(
        id = id,
        title = id,
        amount = amount,
        category = ExpenseCategory.OTHER,
        paidBy = paidBy,
        splitBetween = splitBetween,
        date = LocalDate.of(2026, 7, 9)
    )

    @Test
    fun `sums what each parent paid`() {
        val balance = calculateExpenseBalance(
            expenses = listOf(
                expense("dentist", 120.0, mom),
                expense("school", 68.50, dad),
                expense("swim", 60.0, mom)
            ),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(180.0, balance.momPaid, 0.001)
        assertEquals(68.50, balance.dadPaid, 0.001)
        assertEquals(248.50, balance.total, 0.001)
    }

    @Test
    fun `net is positive when the co-parent owes the user`() {
        // Mom fronts 180, owes half of 248.50 = 124.25, so dad owes her 55.75.
        val balance = calculateExpenseBalance(
            expenses = listOf(
                expense("dentist", 120.0, mom),
                expense("school", 68.50, dad),
                expense("swim", 60.0, mom)
            ),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(55.75, balance.netForCurrentUser, 0.001)
    }

    @Test
    fun `net is negative when the user owes the co-parent`() {
        val balance = calculateExpenseBalance(
            expenses = listOf(
                expense("dentist", 120.0, mom),
                expense("school", 68.50, dad),
                expense("swim", 60.0, mom)
            ),
            currentUserId = dad,
            roleByUid = roles
        )

        assertEquals(-55.75, balance.netForCurrentUser, 0.001)
    }

    @Test
    fun `an unsplit expense counts towards totals but creates no debt`() {
        // Mom buys something only she is responsible for: it is still her spend, but it is not
        // a claim on the other parent.
        val balance = calculateExpenseBalance(
            expenses = listOf(expense("solo", 40.0, mom, splitBetween = emptyList())),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(40.0, balance.momPaid, 0.001)
        assertEquals(40.0, balance.total, 0.001)
        assertEquals(40.0, balance.netForCurrentUser, 0.001)

        val fromDad = calculateExpenseBalance(
            expenses = listOf(expense("solo", 40.0, mom, splitBetween = emptyList())),
            currentUserId = dad,
            roleByUid = roles
        )
        assertEquals(0.0, fromDad.netForCurrentUser, 0.001)
    }

    @Test
    fun `an even split between both parents settles to zero`() {
        val balance = calculateExpenseBalance(
            expenses = listOf(
                expense("a", 100.0, mom),
                expense("b", 100.0, dad)
            ),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(0.0, balance.netForCurrentUser, 0.001)
    }

    @Test
    fun `split is unknown while only one parent is on record`() {
        // The unpaired case: the header must hide the split bar rather than show a 100% bar.
        val balance = calculateExpenseBalance(
            expenses = listOf(expense("dentist", 120.0, mom, splitBetween = listOf(mom))),
            currentUserId = mom,
            roleByUid = mapOf(mom to "mom")
        )

        assertFalse(balance.splitKnown)
    }

    @Test
    fun `split is known once both parents are on record`() {
        val balance = calculateExpenseBalance(
            expenses = listOf(expense("dentist", 120.0, mom)),
            currentUserId = mom,
            roleByUid = roles
        )

        assertTrue(balance.splitKnown)
    }

    @Test
    fun `mom share defaults to half when nothing has been spent`() {
        val balance = calculateExpenseBalance(
            expenses = emptyList(),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(0.0, balance.total, 0.001)
        assertEquals(0.5f, balance.momShareOfPaid, 0.001f)
    }

    @Test
    fun `an expense paid by an unknown uid still counts in the total`() {
        // A payer who is neither parent (stale data, or a profile not yet synced) must not
        // silently vanish from the month total.
        val balance = calculateExpenseBalance(
            expenses = listOf(expense("mystery", 25.0, "uid-someone-else")),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(25.0, balance.total, 0.001)
        assertEquals(0.0, balance.momPaid, 0.001)
        assertEquals(0.0, balance.dadPaid, 0.001)
    }

    @Test
    fun `balances are computed per currency without cross-currency summing`() {
        // A month mixing USD and CZK must not be added into one figure (749 + 15 = 764 would be
        // meaningless) — each currency keeps its own honest total.
        val balances = calculateExpenseBalancesByCurrency(
            expenses = listOf(
                expense("falco", 15.0, mom).copy(currency = "USD"),
                expense("bowling", 749.0, dad).copy(currency = "CZK")
            ),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(2, balances.size)
        // Largest total leads: 749 CZK before 15 USD.
        assertEquals("CZK", balances[0].currency)
        assertEquals(749.0, balances[0].balance.total, 0.001)
        assertEquals("USD", balances[1].currency)
        assertEquals(15.0, balances[1].balance.total, 0.001)
    }

    @Test
    fun `a single-currency month yields one balance`() {
        val balances = calculateExpenseBalancesByCurrency(
            expenses = listOf(
                expense("dentist", 120.0, mom),
                expense("school", 68.50, dad)
            ),
            currentUserId = mom,
            roleByUid = roles
        )

        assertEquals(1, balances.size)
        assertEquals(188.50, balances[0].balance.total, 0.001)
    }

    @Test
    fun `no expenses yields no currency balances`() {
        val balances = calculateExpenseBalancesByCurrency(
            expenses = emptyList(),
            currentUserId = mom,
            roleByUid = roles
        )

        assertTrue(balances.isEmpty())
    }
}
