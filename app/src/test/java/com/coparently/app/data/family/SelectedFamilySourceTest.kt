package com.coparently.app.data.family

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which family this device is showing, and the projection that makes switching cheap.
 *
 * `UserEntity.partnerId` stopped meaning "my co-parent" and started meaning "the co-parent of
 * the family I am showing". Around a hundred and forty call sites read that field and all of
 * them want the second thing, so switching a family is a one-row write rather than a hundred
 * and forty changes — which only holds if the projection is written here and nowhere else, and
 * if the stored choice is validated rather than trusted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectedFamilySourceTest {

    private lateinit var userDao: UserDao
    private lateinit var authService: FirebaseAuthService
    private lateinit var preferences: EncryptedPreferences
    private lateinit var source: SelectedFamilySource

    private val key = "${PreferenceKeys.SELECTED_FAMILY_PREFIX}$ALICE"

    @Before
    fun setUp() {
        userDao = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        every { authService.getCurrentUser() } returns
            mockk<FirebaseUser> { every { uid } returns ALICE }
        source = SelectedFamilySource(userDao, authService, preferences)
    }

    @Test
    fun `both relationships are offered, each as its own family`() = runTest {
        rowIs(partnerIds = listOf(BOB, CAROL))

        assertEquals(
            listOf(
                FamilyOption("alice-uid__bob-uid", BOB),
                FamilyOption("alice-uid__carol-uid", CAROL)
            ),
            source.families()
        )
    }

    @Test
    fun `the first relationship is shown when nothing has been chosen`() = runTest {
        rowIs(partnerIds = listOf(BOB, CAROL))
        every { preferences.getString(key) } returns null

        assertEquals(FamilyOption("alice-uid__bob-uid", BOB), source.selected())
    }

    @Test
    fun `a stored choice survives, and re-points the local row`() = runTest {
        rowIs(partnerIds = listOf(BOB, CAROL))
        every { preferences.getString(key) } returns "alice-uid__carol-uid"

        val entity = slot<UserEntity>()
        coEvery { userDao.updateUser(capture(entity)) } returns Unit
        source.reconcile()

        assertEquals(CAROL, entity.captured.partnerId)
    }

    @Test
    fun `a choice naming a relationship that has ended falls back to a real one`() = runTest {
        // What an unpair performed on the other phone leaves behind: the stored id outlives
        // the relationship, and a device must not go on showing a family it has left.
        rowIs(partnerIds = listOf(BOB))
        every { preferences.getString(key) } returns "alice-uid__carol-uid"

        assertEquals(FamilyOption("alice-uid__bob-uid", BOB), source.selected())
    }

    @Test
    fun `an account with no co-parents shows no family and clears the projection`() = runTest {
        rowIs(partnerIds = emptyList(), partnerId = BOB)

        val entity = slot<UserEntity>()
        coEvery { userDao.updateUser(capture(entity)) } returns Unit
        source.reconcile()

        assertNull(source.selected())
        assertNull(entity.captured.partnerId)
    }

    @Test
    fun `switching to a family the account is not in changes nothing`() = runTest {
        // A stale switcher tap must not blank the co-parent every downstream reader depends on.
        rowIs(partnerIds = listOf(BOB))

        assertNull(source.select("alice-uid__stranger"))
        coVerify(exactly = 0) { userDao.updateUser(any()) }
        coVerify(exactly = 0) { preferences.putString(any(), any()) }
    }

    @Test
    fun `switching stores the choice and re-points the row in one step`() = runTest {
        rowIs(partnerIds = listOf(BOB, CAROL))

        val entity = slot<UserEntity>()
        coEvery { userDao.updateUser(capture(entity)) } returns Unit
        val chosen = source.select("alice-uid__carol-uid")

        assertEquals(FamilyOption("alice-uid__carol-uid", CAROL), chosen)
        assertEquals(CAROL, entity.captured.partnerId)
        coVerify { preferences.putString(key, "alice-uid__carol-uid") }
    }

    @Test
    fun `a projection that already agrees is not rewritten`() = runTest {
        // Room's invalidation tracker re-emits the whole row on any write, and the selection is
        // reconciled on every sync pass; rewriting an unchanged value would wake every screen.
        rowIs(partnerIds = listOf(BOB), partnerId = BOB)
        every { preferences.getString(key) } returns "alice-uid__bob-uid"

        source.reconcile()

        coVerify(exactly = 0) { userDao.updateUser(any()) }
    }

    @Test
    fun `a self-referential entry is not a family`() = runTest {
        rowIs(partnerIds = listOf(ALICE, ""))

        assertEquals(emptyList(), source.families())
    }

    private fun rowIs(partnerIds: List<String>, partnerId: String? = null) {
        val json = partnerIds.joinToString(",", "[", "]") { "\"$it\"" }
        coEvery { userDao.getUserById(ALICE) } returns UserEntity(
            id = ALICE,
            email = "alice@example.com",
            name = "Alice",
            role = "mom",
            colorCode = "#FF4081",
            partnerId = partnerId,
            partnerIdsJson = json
        )
    }

    private companion object {
        const val ALICE = "alice-uid"
        const val BOB = "bob-uid"
        const val CAROL = "carol-uid"
    }
}
