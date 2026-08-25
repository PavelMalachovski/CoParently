package com.coparently.app.presentation.expenses

import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.family.FamilyMemberRef
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.testFamilyMembersSource
import com.coparently.app.presentation.common.testParentsSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Narrowing the month to one child or one pet.
 *
 * The rule under test is the one that is a keystroke away from its opposite: an expense that
 * names nobody appears in the **unfiltered** month only. Reading "names nobody" as "names
 * everybody" would put the family's whole grocery bill under every chip, and every chip would
 * then show the same list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseViewModelMemberFilterTest {

    private val testDispatcher = StandardTestDispatcher()

    private val anya = FamilyMemberRef.Child("c-Anya")
    private val petr = FamilyMemberRef.Child("c-Petr")
    private val barsik = FamilyMemberRef.Pet("p-Barsik")

    private lateinit var viewModel: ExpenseViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true) {
            every { getAllExpenses() } returns flowOf(
                listOf(
                    expense("untagged"),
                    expense("anya", anya),
                    expense("petr", petr),
                    expense("vet", barsik),
                    expense("both", anya, petr)
                )
            )
        }
        val userRepository = mockk<UserRepository>(relaxed = true) {
            coEvery { getCurrentUserId() } returns "u1"
            every { getAllUsers() } returns flowOf(emptyList())
        }
        viewModel = ExpenseViewModel(
            expenseRepository,
            userRepository,
            mockk(relaxed = true),
            mockk<PreferencesRepository>(relaxed = true) {
                every { getDefaultCurrencyFlow() } returns flowOf(SupportedCurrency.DEFAULT)
            },
            mockk<ReceiptTextRecognizer>(relaxed = true),
            mockk(relaxed = true) {
                every { observeSettings() } returns flowOf(null)
                every { agreedRatioOrDefault() } returns SplitRatio.EVEN
            },
            testParentsSource(),
            testFamilyMembersSource(childNames = listOf("Anya", "Petr"), petNames = listOf("Barsik"))
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no chip selected is the whole month, untagged expenses included`() = runTest(testDispatcher) {
        collecting()

        assertEquals(
            setOf("untagged", "anya", "petr", "vet", "both"),
            viewModel.monthExpenses.value.map { it.id }.toSet()
        )
    }

    @Test
    fun `one chip shows what names that child, and not the untagged pile`() = runTest(testDispatcher) {
        collecting()

        viewModel.toggleMemberFilter(anya)
        advanceUntilIdle()

        assertEquals(setOf("anya", "both"), viewModel.monthExpenses.value.map { it.id }.toSet())
    }

    @Test
    fun `two chips are a union, not an intersection`() = runTest(testDispatcher) {
        collecting()

        viewModel.toggleMemberFilter(anya)
        viewModel.toggleMemberFilter(barsik)
        advanceUntilIdle()

        assertEquals(setOf("anya", "both", "vet"), viewModel.monthExpenses.value.map { it.id }.toSet())
    }

    @Test
    fun `a pet can be filtered to, which a single childId could never express`() =
        runTest(testDispatcher) {
            collecting()

            viewModel.toggleMemberFilter(barsik)
            advanceUntilIdle()

            assertEquals(setOf("vet"), viewModel.monthExpenses.value.map { it.id }.toSet())
        }

    @Test
    fun `deselecting the last chip restores the month`() = runTest(testDispatcher) {
        collecting()

        viewModel.toggleMemberFilter(anya)
        advanceUntilIdle()
        viewModel.toggleMemberFilter(anya)
        advanceUntilIdle()

        assertEquals(5, viewModel.monthExpenses.value.size)
    }

    @Test
    fun `a chip for somebody who no longer exists does not strand the screen`() =
        runTest(testDispatcher) {
            // A child removed while their chip was selected leaves a filter with no chip left to
            // tap. Without narrowing the selection to members that still exist, the month would
            // stay empty with no way back to it.
            collecting()

            viewModel.toggleMemberFilter(FamilyMemberRef.Child("c-deleted"))
            advanceUntilIdle()

            assertEquals(5, viewModel.monthExpenses.value.size)
            assertEquals(emptyList(), viewModel.memberFilter.value)
        }

    /** Both flows are `WhileSubscribed`, so nothing is computed until something collects them. */
    private fun TestScope.collecting() {
        backgroundScope.launch(testDispatcher) { viewModel.monthExpenses.collect {} }
        backgroundScope.launch(testDispatcher) { viewModel.memberFilter.collect {} }
        advanceUntilIdle()
    }

    private fun expense(id: String, vararg forMembers: FamilyMemberRef) = Expense(
        id = id,
        forMembers = forMembers.toList(),
        title = id,
        amount = 10.0,
        category = ExpenseCategory.OTHER,
        paidBy = "u1",
        date = LocalDate.now()
    )
}
