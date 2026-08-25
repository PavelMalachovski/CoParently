package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreFamilyDataSource
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.User
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * `caresFor` reaching the family document, from the one place a parent's answer changes.
 *
 * The field says whether this family's app offers child records, pet records or both, as the
 * union of the pair's two answers — and it stopped being a fact about a *person* the moment
 * somebody can co-parent with two others (docs/DESIGN-multi-family.md, M-3). What is pinned
 * here is the write: the profile copy still goes out for a co-parent on an older build, and the
 * family copy goes out beside it, keyed on the caller's own uid because the rule refuses any
 * other key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserCaresForFamilyMirrorTest {

    private lateinit var userDao: UserDao
    private lateinit var authService: FirebaseAuthService
    private lateinit var firestoreUserDataSource: FirestoreUserDataSource
    private lateinit var firestoreFamilyDataSource: FirestoreFamilyDataSource
    private lateinit var repository: UserRepositoryImpl

    @Before
    fun setUp() {
        userDao = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        firestoreUserDataSource = mockk(relaxed = true)
        firestoreFamilyDataSource = mockk(relaxed = true)
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns ALICE }
        every { authService.getCurrentUser() } returns firebaseUser
        coEvery { firestoreUserDataSource.updateUser(any(), any()) } returns Result.success(Unit)
        repository = UserRepositoryImpl(
            userDao,
            authService,
            firestoreUserDataSource,
            firestoreFamilyDataSource,
            mockk<FcmService>(relaxed = true)
        )
    }

    @Test
    fun `a paired parent's answer reaches their family, under their own uid`() = runTest {
        repository.updateUser(user(partnerId = BOB, caresFor = setOf(FamilyKind.PETS)))

        coVerify(exactly = 1) {
            firestoreFamilyDataSource.setCaresFor("alice-uid__bob-uid", ALICE, "PETS")
        }
    }

    @Test
    fun `an unpaired parent writes no family, because there is none to write`() = runTest {
        repository.updateUser(user(partnerId = null, caresFor = setOf(FamilyKind.CHILDREN)))

        coVerify(exactly = 0) { firestoreFamilyDataSource.setCaresFor(any(), any(), any()) }
    }

    @Test
    fun `clearing the answer writes an empty string, not a dropped key`() = runTest {
        // `toStored` returns null for an empty set so a nullable column can tell "unanswered"
        // from "answered nothing"; the family map has no such column, and a dropped key would
        // leave the co-parent's phone reading a stale answer forever.
        repository.updateUser(user(partnerId = BOB, caresFor = emptySet()))

        coVerify(exactly = 1) {
            firestoreFamilyDataSource.setCaresFor("alice-uid__bob-uid", ALICE, "")
        }
    }

    @Test
    fun `a family that does not exist yet does not fail the profile write`() = runTest {
        // Every pair that paired before `families/{id}` existed, until the backfill runs. The
        // profile copy is what those two phones read today, and it has already landed.
        coEvery { firestoreFamilyDataSource.setCaresFor(any(), any(), any()) } throws
            IllegalStateException("NOT_FOUND")

        repository.updateUser(user(partnerId = BOB, caresFor = setOf(FamilyKind.CHILDREN)))

        coVerify(exactly = 1) { firestoreUserDataSource.updateUser(ALICE, any()) }
    }

    private fun user(partnerId: String?, caresFor: Set<FamilyKind>) = User(
        id = ALICE,
        email = "alice@example.com",
        name = "Alice",
        role = "mom",
        colorCode = "#FF4081",
        partnerId = partnerId,
        caresFor = caresFor
    )

    private companion object {
        const val ALICE = "alice-uid"
        const val BOB = "bob-uid"
    }
}
