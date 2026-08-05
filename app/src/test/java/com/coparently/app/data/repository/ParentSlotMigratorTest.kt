package com.coparently.app.data.repository

import androidx.room.withTransaction
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.EventDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The re-stamp runs at the moment a user experiences as "I tapped Accept". A half-completed
 * pass leaves every event they ever created attributed to their co-parent, so these tests
 * pin the two properties that stop that: it touches only rows this user created, and running
 * it twice changes nothing the second time.
 */
class ParentSlotMigratorTest {

    private val eventDao: EventDao = mockk(relaxed = true)
    private val database: CoPlanlyDatabase = mockk()
    private val migrator = ParentSlotMigrator(database, eventDao)

    @Before
    fun setup() {
        // `withTransaction` is an extension on RoomDatabase, so it is mocked statically and
        // made to simply run its block — these tests are about which rows the migration
        // targets, not about Room's transaction machinery.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Int>()) } coAnswers {
            secondArg<suspend () -> Int>().invoke()
        }
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
}
