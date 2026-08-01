package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreExpenseDataSource
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ExpenseRepositoryImpl.syncWithFirestore], guarding the Task 11 fix that
 * closed a `PERMISSION_DENIED` regression: an unfiltered `expenses` collection query is
 * rejected outright by the strict `firestore.rules` (the read rule is keyed on
 * `createdByFirebaseUid`, and Firestore validates *query structure*, not per-document
 * results). The fix scopes the query to the current user plus their paired co-parent via
 * [FirestoreExpenseDataSource.getAllExpenses]; these tests confirm the UID list passed to
 * that call is built correctly in both the paired and unpaired cases, and that a signed-out
 * user never issues a query at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryImplTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var userDao: UserDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreExpenseDataSource: FirestoreExpenseDataSource
    private lateinit var repository: ExpenseRepositoryImpl

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        userDao = mockk()
        firebaseAuthService = mockk()
        firestoreExpenseDataSource = mockk()
        repository = ExpenseRepositoryImpl(expenseDao, userDao, firebaseAuthService, firestoreExpenseDataSource)
    }

    @Test
    fun `syncWithFirestore queries both parents' UIDs when paired`() = runTest {
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = "uidB")
        every { firestoreExpenseDataSource.getAllExpenses(listOf("uidA", "uidB")) } returns emptyFlow()

        repository.syncWithFirestore()

        coVerify(exactly = 1) { firestoreExpenseDataSource.getAllExpenses(listOf("uidA", "uidB")) }
    }

    @Test
    fun `syncWithFirestore queries only the current user's UID when unpaired`() = runTest {
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = null)
        every { firestoreExpenseDataSource.getAllExpenses(listOf("uidA")) } returns emptyFlow()

        repository.syncWithFirestore()

        coVerify(exactly = 1) { firestoreExpenseDataSource.getAllExpenses(listOf("uidA")) }
    }

    @Test
    fun `syncWithFirestore queries only the current user's UID when there is no local user row yet`() = runTest {
        // The Room `users` row (and its partnerId) may not have synced down yet on a fresh
        // install; syncWithFirestore must still scope to the signed-in UID, not skip the
        // filter and fall back to an unfiltered (and therefore rejected) query.
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns null
        every { firestoreExpenseDataSource.getAllExpenses(listOf("uidA")) } returns emptyFlow()

        repository.syncWithFirestore()

        coVerify(exactly = 1) { firestoreExpenseDataSource.getAllExpenses(listOf("uidA")) }
    }

    @Test
    fun `syncWithFirestore does nothing when signed out`() = runTest {
        every { firebaseAuthService.getCurrentUser() } returns null

        repository.syncWithFirestore()

        coVerify(exactly = 0) { firestoreExpenseDataSource.getAllExpenses(any()) }
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
