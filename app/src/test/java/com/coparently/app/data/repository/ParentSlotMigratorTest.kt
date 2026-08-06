package com.coparently.app.data.repository

import androidx.room.withTransaction
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The re-stamp runs at the moment a user experiences as "I tapped Accept". A half-completed
 * pass leaves every event they ever created attributed to their co-parent, so these tests
 * pin the two properties that stop that: it touches only rows this user created, and running
 * it twice changes nothing the second time.
 */
class ParentSlotMigratorTest {

    private val eventDao: EventDao = mockk(relaxed = true)
    private val database: CoPlanlyDatabase = mockk()
    private val custodyModelRepository: CustodyModelRepository = mockk(relaxed = true)
    private val migrator = ParentSlotMigrator(database, eventDao, custodyModelRepository)

    @Before
    fun setup() {
        // `withTransaction` is an extension on RoomDatabase, so it is mocked statically and
        // made to simply run its block — these tests are about which rows the migration
        // targets, not about Room's transaction machinery.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Int>()) } coAnswers {
            secondArg<suspend () -> Int>().invoke()
        }
        // No active pattern by default; the custody-complement tests below stub one in.
        coEvery { custodyModelRepository.getActiveModelSync() } returns null
    }

    @After
    fun tearDown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    @Test
    fun `re-stamps only rows this user created`() = runTest {
        coEvery { eventDao.reslotOwner(any(), any(), any()) } returns 3
        coEvery { eventDao.reslotPickup(any(), any(), any()) } returns 1

        migrator.reslot(from = "mom", to = "dad", myUid = "u1")

        coVerify(exactly = 1) { eventDao.reslotOwner("mom", "dad", "u1") }
        coVerify(exactly = 1) { eventDao.reslotPickup("mom", "dad", "u1") }
    }

    @Test
    fun `a slot that did not change is a no-op`() = runTest {
        migrator.reslot(from = "mom", to = "mom", myUid = "u1")

        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 0) { eventDao.reslotPickup(any(), any(), any()) }
    }

    @Test
    fun `running it twice is harmless because the second pass matches nothing`() = runTest {
        coEvery { eventDao.reslotOwner("mom", "dad", "u1") } returnsMany listOf(3, 0)
        coEvery { eventDao.reslotPickup("mom", "dad", "u1") } returnsMany listOf(1, 0)

        val first = migrator.reslot(from = "mom", to = "dad", myUid = "u1")
        val second = migrator.reslot(from = "mom", to = "dad", myUid = "u1")

        assertEquals(4, first)
        assertEquals(0, second)
    }

    @Test
    fun `a blank uid is refused rather than re-stamping everything`() = runTest {
        val failure = runCatching { migrator.reslot(from = "mom", to = "dad", myUid = "") }
        assert(failure.isFailure) { "a blank uid must not match every row in the table" }
        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
    }

    // ---- reslotIfSlotChanged: the second entry point, for a slot that flipped behind a
    // periodic sync rather than a UI action ------------------------------------------------

    @Test
    fun `a profile arriving with a different slot re-stamps this user's rows`() = runTest {
        coEvery { eventDao.reslotOwner(any(), any(), any()) } returns 3
        coEvery { eventDao.reslotPickup(any(), any(), any()) } returns 1

        migrator.reslotIfSlotChanged(myUid = "u1", previousRole = "mom", newRole = "dad")

        coVerify(exactly = 1) { eventDao.reslotOwner("mom", "dad", "u1") }
        coVerify(exactly = 1) { eventDao.reslotPickup("mom", "dad", "u1") }
    }

    @Test
    fun `a profile arriving with the same slot changes nothing`() = runTest {
        migrator.reslotIfSlotChanged(myUid = "u1", previousRole = "mom", newRole = "mom")

        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 0) { eventDao.reslotPickup(any(), any(), any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
    }

    @Test
    fun `the first profile this device has ever seen is not treated as a change`() = runTest {
        // A real active pattern is stubbed in on purpose: if the null-previous guard were
        // dropped, "null -> dad" would read as a flip and this pattern would be complemented
        // and saved, inverting a schedule that just arrived, correctly, from the co-parent's
        // shared document. Without this stub the test would pass even with the guard removed,
        // because there would be nothing for a dropped guard to wrongly act on.
        coEvery { custodyModelRepository.getActiveModelSync() } returns custodyModel(setOf(0, 1))

        migrator.reslotIfSlotChanged(myUid = "u1", previousRole = null, newRole = "dad")

        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 0) { eventDao.reslotPickup(any(), any(), any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
    }

    @Test
    fun `a slot change complements the active custody model`() = runTest {
        val active = custodyModel(momDays = setOf(0, 1, 2))
        coEvery { custodyModelRepository.getActiveModelSync() } returns active
        val saved = slot<CustodyModel>()
        coEvery { custodyModelRepository.saveReslotted(capture(saved)) } returns Unit

        migrator.reslotIfSlotChanged(myUid = "u1", previousRole = "mom", newRole = "dad")

        assertEquals(active.complemented().momDayIndices, saved.captured.momDayIndices)
    }

    @Test
    fun `a slot change with no active custody model is not an error`() = runTest {
        coEvery { custodyModelRepository.getActiveModelSync() } returns null

        val failure = runCatching {
            migrator.reslotIfSlotChanged(myUid = "u1", previousRole = "mom", newRole = "dad")
        }

        assertNull(failure.exceptionOrNull())
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
    }

    private fun custodyModel(momDays: Set<Int>) = CustodyModel(
        id = "pattern-1",
        modelType = CustodyModelType.CUSTOM,
        patternDays = 7,
        momDayIndices = momDays,
        startDate = LocalDate.of(2026, 8, 1)
    )
}
