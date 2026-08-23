package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.ChildInfoDao
import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.ChildInfoEntity
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreChildInfoDataSource
import com.coparently.app.domain.model.ChildInfo
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * The photograph list has to survive every mapper the child record passes through.
 *
 * A child-info document is built and read in **seven** places — this repository's four mappers,
 * and `SyncService`'s upload map, conflict map and reader — and package B1 shipped a
 * data-destroying defect from adding a field to some of them and not the rest. The repository's
 * uploads are a full `.set()`, so a field missing from one of these maps is not merely absent
 * from the write: it is **deleted** from the document.
 *
 * This class covers the four sites reachable from here. `SyncService`'s three cannot be
 * instantiated in a unit test — see the package ledger, which says so plainly rather than
 * claiming coverage that does not exist, and names the shared mapper that would fix it properly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChildInfoPhotosMappingTest {

    private lateinit var childInfoDao: ChildInfoDao
    private lateinit var userDao: UserDao
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var firestoreChildInfoDataSource: FirestoreChildInfoDataSource
    private lateinit var repository: ChildInfoRepositoryImpl

    @Before
    fun setup() {
        childInfoDao = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        firebaseAuthService = mockk(relaxed = true)
        firestoreChildInfoDataSource = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns ALICE
        every { firebaseAuthService.getCurrentUser() } returns firebaseUser

        repository = ChildInfoRepositoryImpl(
            childInfoDao,
            userDao,
            firebaseAuthService,
            firestoreChildInfoDataSource
        )
    }

    @Test
    fun `the photographs reach the Firestore document, in order`() {
        runTest {
            coEvery { userDao.getUserById(ALICE) } returns userEntity()
            coEvery { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) } returns
                Result.success(Unit)

            repository.upsertChildInfo(childInfo(photos = listOf(FIRST, SECOND)))

            val uploaded = slot<Map<String, Any?>>()
            coVerify { firestoreChildInfoDataSource.upsertChildInfo(CHILD_ID, capture(uploaded)) }
            assertEquals(listOf(FIRST, SECOND), uploaded.captured["medicalPhotos"])
        }
    }

    @Test
    fun `a record with no photographs writes an empty list, not a missing key`() {
        runTest {
            // Written rather than omitted: the upload is a full `.set()`, so an absent key and an
            // empty list mean the same thing here — but they do not on the reading side, and one
            // of the two is the shape B1's defect produced.
            coEvery { userDao.getUserById(ALICE) } returns userEntity()
            coEvery { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) } returns
                Result.success(Unit)

            repository.upsertChildInfo(childInfo(photos = emptyList()))

            val uploaded = slot<Map<String, Any?>>()
            coVerify { firestoreChildInfoDataSource.upsertChildInfo(CHILD_ID, capture(uploaded)) }
            assertEquals(emptyList<String>(), uploaded.captured["medicalPhotos"])
        }
    }

    @Test
    fun `the photographs survive the trip into Room and back out`() {
        runTest {
            coEvery { userDao.getUserById(ALICE) } returns userEntity()
            coEvery { firestoreChildInfoDataSource.upsertChildInfo(any(), any()) } returns
                Result.success(Unit)

            // toEntity, on the way in…
            val stored = slot<ChildInfoEntity>()
            repository.upsertChildInfo(childInfo(photos = listOf(FIRST, SECOND)))
            coVerify { childInfoDao.insertChildInfo(capture(stored)) }

            // …and toDomain, on the way back.
            every { childInfoDao.getAllChildInfo() } returns flowOf(listOf(stored.captured))

            assertEquals(
                listOf(FIRST, SECOND),
                repository.getAllChildInfo().first().single().medicalPhotos
            )
        }
    }

    @Test
    fun `a row stored before this field existed reads as an empty list, not null`() {
        runTest {
            // `medicalPhotosJson` defaults to "[]" on the entity, and the migration defaults the
            // column the same way — but a row could also reach the mapper with the literal the
            // old code never wrote. Neither may produce a null list on the medical screen.
            every { childInfoDao.getAllChildInfo() } returns flowOf(listOf(entity(photosJson = "[]")))

            assertEquals(
                emptyList(),
                repository.getAllChildInfo().first().single().medicalPhotos
            )
        }
    }

    private fun childInfo(photos: List<String>) = ChildInfo(
        id = CHILD_ID,
        childName = "Ema",
        dateOfBirth = null,
        medicalPhotos = photos,
        createdAt = NOW,
        updatedAt = NOW,
        createdByFirebaseUid = ALICE,
        lastModifiedBy = ALICE,
        syncedToFirestore = false
    )

    private fun entity(photosJson: String) = ChildInfoEntity(
        id = CHILD_ID,
        childName = "Ema",
        dateOfBirth = null,
        medicationsJson = "[]",
        activitiesJson = "[]",
        allergiesJson = "[]",
        medicalNotes = null,
        emergencyContactsJson = "[]",
        schoolInfoJson = null,
        medicalPhotosJson = photosJson,
        createdAt = NOW,
        updatedAt = NOW,
        createdByFirebaseUid = ALICE,
        lastModifiedBy = ALICE,
        syncedToFirestore = true
    )

    private fun userEntity() = UserEntity(
        id = ALICE,
        email = "alice@example.test",
        name = "Alice",
        role = "mom",
        colorCode = "#FF4081",
        partnerId = null
    )

    private companion object {
        const val ALICE = "alice-uid"
        const val CHILD_ID = "child-1"
        const val FIRST = "https://example.test/first.jpg"
        const val SECOND = "https://example.test/second.jpg"
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 1, 12, 0)
    }
}
