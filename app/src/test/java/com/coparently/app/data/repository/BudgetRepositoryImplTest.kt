package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.BudgetDao
import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.BudgetEntity
import com.coparently.app.data.local.entity.ExpenseEntity
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreBudgetDataSource
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [BudgetRepositoryImpl.observeRemote], guarding the fix for the
 * `budgets` collection's `PERMISSION_DENIED` regression (Task 11 follow-up): the strict
 * `firestore.rules` gate `budgets` reads on `createdByFirebaseUid`, so an unfiltered query
 * is rejected outright by Firestore's query-structure validation, exactly like `expenses`
 * before it was fixed. These tests mirror [ExpenseRepositoryImplTest] and confirm the UID
 * list passed to [FirestoreBudgetDataSource.getAllBudgets] is built correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetRepositoryImplTest {

    private lateinit var budgetDao: BudgetDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var userDao: UserDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreBudgetDataSource: FirestoreBudgetDataSource
    private lateinit var repository: BudgetRepositoryImpl

    @Before
    fun setup() {
        budgetDao = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        userDao = mockk()
        firebaseAuthService = mockk()
        firestoreBudgetDataSource = mockk()
        repository = BudgetRepositoryImpl(
            budgetDao, expenseDao, userDao, firebaseAuthService, firestoreBudgetDataSource
        )
    }

    // ---- what a budget is scoped to -----------------------------------------

    @Test
    fun `a budget that names nobody is charged everything in its category`() = runTest {
        // What every budget in production is: the field that could have scoped one was never
        // written by any screen. An upgrade must not start excluding spending from it.
        budgetIs(forMembersJson = "[]")
        categorySpending(
            expenseOf(20.0, forMembersJson = "[]"),
            expenseOf(30.0, forMembersJson = """["child:c1"]"""),
            expenseOf(50.0, forMembersJson = """["pet:p1"]""")
        )

        assertEquals(100.0, repository.getSpentForBudget("b1"), 0.001)
    }

    @Test
    fun `a scoped budget is charged only what names it back`() = runTest {
        budgetIs(forMembersJson = """["child:c1"]""")
        categorySpending(
            expenseOf(20.0, forMembersJson = "[]"),
            expenseOf(30.0, forMembersJson = """["child:c1"]"""),
            expenseOf(50.0, forMembersJson = """["child:c2"]""")
        )

        // 30 only. The untagged 20 is deliberately *not* charged to a child's budget: naming
        // nobody is not the same as naming everybody, or every child's budget would carry the
        // family's whole grocery bill. Same rule as the filter chips.
        assertEquals(30.0, repository.getSpentForBudget("b1"), 0.001)
    }

    @Test
    fun `a budget naming a pet is charged the vet, which a childId could not express`() = runTest {
        budgetIs(forMembersJson = """["pet:p1"]""")
        categorySpending(
            expenseOf(80.0, forMembersJson = """["pet:p1"]"""),
            expenseOf(30.0, forMembersJson = """["child:c1"]""")
        )

        assertEquals(80.0, repository.getSpentForBudget("b1"), 0.001)
    }

    @Test
    fun `spending outside the current month is never charged`() = runTest {
        budgetIs(forMembersJson = "[]")
        categorySpending(
            expenseOf(20.0, forMembersJson = "[]"),
            expenseOf(999.0, forMembersJson = "[]", on = LocalDate.now().minusMonths(2))
        )

        assertEquals(20.0, repository.getSpentForBudget("b1"), 0.001)
    }

    private fun budgetIs(forMembersJson: String) {
        coEvery { budgetDao.getBudgetById("b1") } returns BudgetEntity(
            id = "b1",
            forMembersJson = forMembersJson,
            category = "OTHER",
            monthlyLimit = 1000.0,
            createdAt = LocalDateTime.now()
        )
    }

    private fun categorySpending(vararg expenses: ExpenseEntity) {
        every { expenseDao.getExpensesByCategory("OTHER") } returns flowOf(expenses.toList())
    }

    private fun expenseOf(
        amount: Double,
        forMembersJson: String,
        on: LocalDate = LocalDate.now()
    ) = ExpenseEntity(
        id = "e$amount$forMembersJson",
        forMembersJson = forMembersJson,
        title = "spend",
        amount = amount,
        category = "OTHER",
        paidBy = "uidA",
        date = on,
        createdAt = LocalDateTime.now()
    )

    @Test
    fun `observeRemote queries both parents' UIDs when paired`() = runTest {
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = "uidB")
        every { firestoreBudgetDataSource.getAllBudgets(listOf("uidA", "uidB")) } returns emptyFlow()

        repository.observeRemote()

        coVerify(exactly = 1) { firestoreBudgetDataSource.getAllBudgets(listOf("uidA", "uidB")) }
    }

    @Test
    fun `observeRemote queries only the current user's UID when unpaired`() = runTest {
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = null)
        every { firestoreBudgetDataSource.getAllBudgets(listOf("uidA")) } returns emptyFlow()

        repository.observeRemote()

        coVerify(exactly = 1) { firestoreBudgetDataSource.getAllBudgets(listOf("uidA")) }
    }

    @Test
    fun `observeRemote does nothing when signed out`() = runTest {
        every { firebaseAuthService.getCurrentUser() } returns null

        repository.observeRemote()

        coVerify(exactly = 0) { firestoreBudgetDataSource.getAllBudgets(any()) }
    }

    @Test
    fun `addBudget stamps createdByFirebaseUid on the synced document`() = runTest {
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = "uidB")
        val dataSlot = io.mockk.slot<Map<String, Any>>()
        coEvery { firestoreBudgetDataSource.setBudget(any(), capture(dataSlot)) } returns Unit

        repository.addBudget(
            com.coparently.app.domain.model.Budget(
                id = "b1",
                category = com.coparently.app.domain.model.ExpenseCategory.OTHER,
                monthlyLimit = 100.0
            )
        )

        coVerify(exactly = 1) { firestoreBudgetDataSource.setBudget("b1", any()) }
        org.junit.Assert.assertEquals("uidA", dataSlot.captured["createdByFirebaseUid"])
        // And the relationship it belongs to, derived from the live pairing at create time.
        org.junit.Assert.assertEquals("uidA__uidB", dataSlot.captured["familyId"])
    }

    @Test
    fun `a budget created while unpaired names no family`() = runTest {
        // Null, not an invented id for a pair of one — and `""` on the wire, because a Firestore
        // map value cannot be null. The backfill names it once there is somebody to name.
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = null)
        val dataSlot = io.mockk.slot<Map<String, Any>>()
        coEvery { firestoreBudgetDataSource.setBudget(any(), capture(dataSlot)) } returns Unit

        repository.addBudget(
            com.coparently.app.domain.model.Budget(
                id = "b1",
                category = com.coparently.app.domain.model.ExpenseCategory.OTHER,
                monthlyLimit = 100.0
            )
        )

        org.junit.Assert.assertEquals("", dataSlot.captured["familyId"])
    }

    @Test
    fun `updateBudget preserves the existing owner instead of re-stamping the caller`() = runTest {
        // uidB is editing a budget originally created by uidA (e.g. the paired co-parent).
        // firestore.rules rejects an update whose createdByFirebaseUid differs from the
        // stored document, so the write must keep "uidA", not switch to the caller "uidB".
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidB" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { firestoreBudgetDataSource.getBudget("b1") } returns mapOf(
            "id" to "b1",
            "createdByFirebaseUid" to "uidA"
        )
        val dataSlot = io.mockk.slot<Map<String, Any>>()
        coEvery { firestoreBudgetDataSource.setBudget(any(), capture(dataSlot)) } returns Unit

        repository.updateBudget(
            com.coparently.app.domain.model.Budget(
                id = "b1",
                category = com.coparently.app.domain.model.ExpenseCategory.OTHER,
                monthlyLimit = 150.0
            )
        )

        coVerify(exactly = 1) { firestoreBudgetDataSource.setBudget("b1", any()) }
        org.junit.Assert.assertEquals("uidA", dataSlot.captured["createdByFirebaseUid"])
    }

    private fun userEntity(id: String, partnerId: String?) = UserEntity(
        id = id,
        email = "$id@example.com",
        name = id,
        role = "mom",
        colorCode = "#FF4081",
        partnerId = partnerId
    )
}
