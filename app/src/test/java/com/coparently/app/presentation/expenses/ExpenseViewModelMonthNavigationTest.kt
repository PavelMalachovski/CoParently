package com.coparently.app.presentation.expenses

import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.ReceiptStorage
import com.coparently.app.domain.repository.UserRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Unit tests for [ExpenseViewModel]'s month navigation — the fix for receipt-dated expenses
 * silently vanishing from the current-month-only list. Verifies that [ExpenseViewModel.monthExpenses]
 * follows [ExpenseViewModel.selectedMonth] as the user pages back and forth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseViewModelMonthNavigationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var userRepository: UserRepository
    private lateinit var receiptStorage: ReceiptStorage
    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var receiptTextRecognizer: ReceiptTextRecognizer
    private lateinit var viewModel: ExpenseViewModel

    private val thisMonthExpense = Expense(
        id = "current",
        title = "falco",
        amount = 15.0,
        category = ExpenseCategory.OTHER,
        paidBy = "u1",
        date = LocalDate.now()
    )
    private val lastMonthExpense = Expense(
        id = "past",
        title = "bowling",
        amount = 749.0,
        category = ExpenseCategory.OTHER,
        paidBy = "u1",
        date = LocalDate.now().minusMonths(1)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        expenseRepository = mockk(relaxed = true) {
            every { getAllExpenses() } returns flowOf(listOf(thisMonthExpense, lastMonthExpense))
        }
        userRepository = mockk(relaxed = true) {
            coEvery { getCurrentUserId() } returns "u1"
            every { getAllUsers() } returns flowOf(emptyList())
        }
        receiptStorage = mockk(relaxed = true)
        preferencesRepository = mockk(relaxed = true) {
            every { getDefaultCurrencyFlow() } returns flowOf(SupportedCurrency.DEFAULT)
        }
        receiptTextRecognizer = mockk(relaxed = true)
        viewModel = ExpenseViewModel(
            expenseRepository,
            userRepository,
            receiptStorage,
            preferencesRepository,
            receiptTextRecognizer
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `monthExpenses shows only the current month by default`() = runTest(testDispatcher) {
        backgroundScope.launch(testDispatcher) { viewModel.monthExpenses.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("current"), viewModel.monthExpenses.value.map { it.id })
    }

    @Test
    fun `paging to the previous month reveals that month's expenses`() = runTest(testDispatcher) {
        backgroundScope.launch(testDispatcher) { viewModel.monthExpenses.collect {} }
        advanceUntilIdle()

        viewModel.showPreviousMonth()
        advanceUntilIdle()
        assertEquals(listOf("past"), viewModel.monthExpenses.value.map { it.id })

        viewModel.showNextMonth()
        advanceUntilIdle()
        assertEquals(listOf("current"), viewModel.monthExpenses.value.map { it.id })
    }
}
