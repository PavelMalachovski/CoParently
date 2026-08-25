package com.coparently.app.presentation.expenses

import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.ParentsSource
import com.coparently.app.presentation.common.testFamilyMembersSource
import com.coparently.app.presentation.common.testParentsSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who a shared expense is recorded as being split between.
 *
 * The case worth a file of its own: the Add Expense screen is its own route with its own
 * `hiltViewModel()`, and it collects `agreedRatio` but never `parents`. `parents` is shared with
 * `WhileSubscribed`, so in that instance it had never emitted and answered "no co-parent" for
 * every save — `splitBetween` named the payer alone, the payer's own month read correctly, and
 * the co-parent's showed nothing owed at all. Nothing in the test suite noticed, because nothing
 * asserted on what `addExpense` writes.
 *
 * These tests deliberately never collect `viewModel.parents`, so they reproduce that route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseViewModelSplitTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var userRepository: UserRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        expenseRepository = mockk(relaxed = true) {
            every { getAllExpenses() } returns flowOf(emptyList())
        }
        userRepository = mockk(relaxed = true) {
            coEvery { getCurrentUserId() } returns ME
            every { getAllUsers() } returns flowOf(emptyList())
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a shared expense names both parents, without anyone collecting parents`() = runTest {
        val viewModel = createViewModel(paired = true)
        advanceUntilIdle()

        viewModel.addExpense(
            title = "school trip",
            amount = 900.0,
            category = ExpenseCategory.OTHER,
            currency = SupportedCurrency.DEFAULT.code
        )
        advanceUntilIdle()

        assertEquals(listOf(ME, PARTNER), saved().splitBetween)
    }

    @Test
    fun `an expense marked not shared names nobody, so it is a claim on no one`() = runTest {
        val viewModel = createViewModel(paired = true)
        advanceUntilIdle()

        viewModel.addExpense(
            title = "my own haircut",
            amount = 400.0,
            category = ExpenseCategory.OTHER,
            currency = SupportedCurrency.DEFAULT.code,
            shared = false
        )
        advanceUntilIdle()

        val expense = saved()
        assertEquals(emptyList<String>(), expense.splitBetween)
        // No ratio either: a row nobody shares must not carry a price for splitting it.
        assertNull(expense.splitBasisPoints)
    }

    @Test
    fun `an unpaired account names only the payer, because there is nobody to owe a share`() =
        runTest {
            val viewModel = createViewModel(paired = false)
            advanceUntilIdle()

            viewModel.addExpense(
                title = "nappies",
                amount = 300.0,
                category = ExpenseCategory.OTHER,
                currency = SupportedCurrency.DEFAULT.code
            )
            advanceUntilIdle()

            assertEquals(listOf(ME), saved().splitBetween)
        }

    @Test
    fun `a one-off override is stamped on the expense, not on the family agreement`() = runTest {
        val viewModel = createViewModel(paired = true)
        advanceUntilIdle()

        viewModel.addExpense(
            title = "winter coat",
            amount = 2_000.0,
            category = ExpenseCategory.OTHER,
            currency = SupportedCurrency.DEFAULT.code,
            splitOverride = SplitRatio.ofMomPercent(SEVENTY)
        )
        advanceUntilIdle()

        assertEquals(SEVENTY_IN_BASIS_POINTS, saved().splitBasisPoints)
    }

    private fun saved(): Expense {
        val expense = slot<Expense>()
        coVerify { expenseRepository.addExpense(capture(expense)) }
        return expense.captured
    }

    private fun createViewModel(paired: Boolean): ExpenseViewModel = ExpenseViewModel(
        expenseRepository,
        userRepository,
        mockk(relaxed = true),
        mockk(relaxed = true) {
            every { getDefaultCurrencyFlow() } returns flowOf(SupportedCurrency.DEFAULT)
        },
        mockk<ReceiptTextRecognizer>(relaxed = true),
        mockk(relaxed = true) {
            every { observeSettings() } returns flowOf(null)
            every { agreedRatioOrDefault() } returns SplitRatio.EVEN
        },
        parentsSource(paired),
        testFamilyMembersSource()
    )

    /**
     * A source that knows the pairing, exactly as the real one does after `ensureProfile` — the
     * point being that the ViewModel must reach it without a subscriber on `parents`.
     */
    private fun parentsSource(paired: Boolean): ParentsSource = testParentsSource(
        me = User(
            id = ME,
            email = "olya@example.test",
            name = "Olya",
            role = "mom",
            colorCode = "#FF4081"
        ),
        partner = if (paired) {
            PartnerSummary(id = PARTNER, name = "Pavel", email = "p@example.test", pairedSinceMillis = null)
        } else {
            null
        }
    )

    private companion object {
        const val ME = "uid-me"
        const val PARTNER = "uid-partner"

        /** Slot 1 takes seventy percent, the shape a real renegotiation produces. */
        const val SEVENTY = 70
        const val SEVENTY_IN_BASIS_POINTS = 7_000
    }
}
