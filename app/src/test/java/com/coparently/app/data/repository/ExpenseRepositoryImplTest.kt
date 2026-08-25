package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ExpenseDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreExpenseDataSource
import com.coparently.app.domain.family.FamilyMemberRef
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [ExpenseRepositoryImpl.observeRemote], guarding the Task 11 fix that
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
        repository = ExpenseRepositoryImpl(
            expenseDao,
            userDao,
            firebaseAuthService,
            firestoreExpenseDataSource,
            mockk(relaxed = true)
        )
    }

    @Test
    fun `observeRemote queries the family, not the two authors`() = runTest {
        // The shape is the isolation. A filter on the author returns every family that author
        // is in, and while any branch of the read rule mentioned `isPartnerOf`, Firestore
        // served that query — see `firestore-tests/rules/family-isolation.test.js`.
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = "uidB")
        every { firestoreExpenseDataSource.getAllExpenses("uidA__uidB") } returns emptyFlow()

        repository.observeRemote()

        coVerify(exactly = 1) { firestoreExpenseDataSource.getAllExpenses("uidA__uidB") }
    }

    @Test
    fun `observeRemote asks for no family when unpaired`() = runTest {
        // Null rather than a query for this user alone: an unpaired account has nothing shared
        // to download, and the data source closes without issuing a read that would be denied.
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns userEntity(id = "uidA", partnerId = null)
        every { firestoreExpenseDataSource.getAllExpenses(null) } returns emptyFlow()

        repository.observeRemote()

        coVerify(exactly = 1) { firestoreExpenseDataSource.getAllExpenses(null) }
    }

    @Test
    fun `observeRemote asks for no family when there is no local user row yet`() = runTest {
        // The Room `users` row (and its partnerId) may not have synced down yet on a fresh
        // install. Without a partner there is no family id to derive, and the honest answer is
        // null — never an unfiltered read, which the rules reject outright.
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns "uidA" }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById("uidA") } returns null
        every { firestoreExpenseDataSource.getAllExpenses(null) } returns emptyFlow()

        repository.observeRemote()

        coVerify(exactly = 1) { firestoreExpenseDataSource.getAllExpenses(null) }
    }

    // ---- ownership on update ------------------------------------------------

    @Test
    fun `updateExpense keeps the original owner when the co-parent edits`() = runTest {
        // `firestore.rules` makes createdByFirebaseUid immutable on update. Re-stamping the
        // editor's uid (which is what delegating to addExpense used to do) got the write
        // denied, and the edit sat in Room with syncedToFirestore = false forever.
        signIn("uidB")
        coEvery { firestoreExpenseDataSource.getExpense("e1") } returns
            mapOf("createdByFirebaseUid" to "uidA")
        val captured = slot<Map<String, Any>>()
        coEvery { firestoreExpenseDataSource.setExpense(any(), capture(captured)) } returns Unit

        repository.updateExpense(expense())

        assertEquals("uidA", captured.captured["createdByFirebaseUid"])
    }

    @Test
    fun `updateExpense falls back to the caller when the document does not exist remotely`() = runTest {
        signIn("uidB")
        coEvery { firestoreExpenseDataSource.getExpense("e1") } returns null
        val captured = slot<Map<String, Any>>()
        coEvery { firestoreExpenseDataSource.setExpense(any(), capture(captured)) } returns Unit

        repository.updateExpense(expense())

        assertEquals("uidB", captured.captured["createdByFirebaseUid"])
    }

    @Test
    fun `addExpense stamps the current user as owner`() = runTest {
        signIn("uidA")
        val captured = slot<Map<String, Any>>()
        coEvery { firestoreExpenseDataSource.setExpense(any(), capture(captured)) } returns Unit

        repository.addExpense(expense())

        assertEquals("uidA", captured.captured["createdByFirebaseUid"])
        // A brand-new document has no prior owner, so there is nothing to read back.
        coVerify(exactly = 0) { firestoreExpenseDataSource.getExpense(any()) }
    }

    @Test
    fun `updateExpense keeps the expense locally when the remote write is rejected`() = runTest {
        signIn("uidB")
        coEvery { firestoreExpenseDataSource.getExpense("e1") } returns
            mapOf("createdByFirebaseUid" to "uidA")
        coEvery { firestoreExpenseDataSource.setExpense(any(), any()) } throws
            IllegalStateException("PERMISSION_DENIED")

        // Must not propagate: the row is already in Room and will re-sync later.
        repository.updateExpense(expense())

        coVerify { expenseDao.insertExpense(any()) }
    }

    @Test
    fun `observeRemote does nothing when signed out`() = runTest {
        every { firebaseAuthService.getCurrentUser() } returns null

        repository.observeRemote()

        coVerify(exactly = 0) { firestoreExpenseDataSource.getAllExpenses(any()) }
    }

    /**
     * Puts [uid] in the auth service, and answers the one Room read the write paths make.
     *
     * `addExpense` and `updateExpense` both call `announce()`, which resolves the sender's
     * display name from `users/{uid}` — outside the try/catch that guards the Firestore write,
     * so an unstubbed strict mock there fails the test rather than being swallowed. `null` is
     * the honest answer: the row may not have synced down yet, and the announcement then names
     * nobody. Which name it carries is `ActivityAnnouncerTest`'s subject, not this file's.
     */
    private fun signIn(uid: String) {
        val firebaseUser = mockk<FirebaseUser> { every { this@mockk.uid } returns uid }
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser
        coEvery { userDao.getUserById(uid) } returns null
    }

    // ---- who the expense is about -------------------------------------------

    @Test
    fun `the document names the members, and no longer carries childId`() = runTest {
        signIn("uidA")
        val captured = slot<Map<String, Any>>()
        coEvery { firestoreExpenseDataSource.setExpense(any(), capture(captured)) } returns Unit

        repository.addExpense(
            expense().copy(
                forMembers = listOf(FamilyMemberRef.Child("c1"), FamilyMemberRef.Pet("p1"))
            )
        )

        assertEquals(listOf("child:c1", "pet:p1"), captured.captured["forMembers"])
        // Dropped rather than mirrored: no client ever set it, so every document in production
        // carries "", and a single id could not hold the pet anyway.
        assertNull(captured.captured["childId"])
    }

    @Test
    fun `an expense about nobody writes an empty list, not an absent key`() = runTest {
        // The read side reads "no members" as "the whole family". An absent key would work
        // today and break the moment anything iterates the document's fields.
        signIn("uidA")
        val captured = slot<Map<String, Any>>()
        coEvery { firestoreExpenseDataSource.setExpense(any(), capture(captured)) } returns Unit

        repository.addExpense(expense())

        assertEquals(emptyList<String>(), captured.captured["forMembers"])
    }

    private fun expense() = Expense(
        id = "e1",
        title = "School trip",
        amount = 42.0,
        currency = "CZK",
        category = ExpenseCategory.EDUCATION,
        paidBy = "mom",
        date = LocalDate.of(2026, 8, 1),
        createdAt = LocalDateTime.of(2026, 8, 1, 9, 0)
    )

    private fun userEntity(id: String, partnerId: String?) = UserEntity(
        id = id,
        email = "$id@example.com",
        name = id,
        role = "mom",
        colorCode = "#FF4081",
        partnerId = partnerId
    )
}
