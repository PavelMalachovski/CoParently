package com.coparently.app.data.repository

import androidx.room.withTransaction
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
 *
 * [ParentSlotMigrator.reslotIfSlotChanged]'s tests below back the "before" side with
 * [markerStore], a plain map standing in for [EncryptedPreferences] — not with Room's `role`,
 * which a round-1 review found could never be the "before" side (never written by the accept
 * path, seeded with a placeholder on profile creation, written non-atomically with the
 * re-stamp). The two regression tests near the bottom pin the failures that comparison caused.
 */
class ParentSlotMigratorTest {

    private val eventDao: EventDao = mockk(relaxed = true)
    private val database: CoPlanlyDatabase = mockk()
    private val custodyModelRepository: CustodyModelRepository = mockk(relaxed = true)
    private val encryptedPreferences: EncryptedPreferences = mockk(relaxed = true)
    private val migrator = ParentSlotMigrator(database, eventDao, custodyModelRepository, encryptedPreferences)

    /**
     * A real backing map behind the preferences mock: a marker one call writes is the marker
     * the next call reads. Without this, the regression tests below (which call the migrator
     * twice and depend on the second call seeing what the first one wrote) would need to
     * simulate persistence by hand.
     */
    private val markerStore = mutableMapOf<String, String>()

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

        every { encryptedPreferences.getString(any()) } answers { markerStore[firstArg()] }
        every { encryptedPreferences.getString(any(), any()) } answers {
            markerStore[firstArg()] ?: secondArg()
        }
        every { encryptedPreferences.putString(any(), any()) } answers {
            markerStore[firstArg()] = secondArg()
        }
    }

    @After
    fun tearDown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    private fun markerKey(uid: String) = "${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}$uid"

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

    @Test
    fun `reslot records the new slot as this user's marker`() = runTest {
        migrator.reslot(from = "mom", to = "dad", myUid = "u1")

        assertEquals("dad", markerStore[markerKey("u1")])
    }

    // ---- reslotIfSlotChanged: the second entry point, for a slot that flipped behind a
    // periodic sync rather than a UI action ------------------------------------------------

    @Test
    fun `a profile arriving with a different slot re-stamps this user's rows`() = runTest {
        markerStore[markerKey("u1")] = "mom"
        coEvery { eventDao.reslotOwner(any(), any(), any()) } returns 3
        coEvery { eventDao.reslotPickup(any(), any(), any()) } returns 1

        migrator.reslotIfSlotChanged(myUid = "u1", newRole = "dad")

        coVerify(exactly = 1) { eventDao.reslotOwner("mom", "dad", "u1") }
        coVerify(exactly = 1) { eventDao.reslotPickup("mom", "dad", "u1") }
    }

    @Test
    fun `a profile arriving with the same slot changes nothing`() = runTest {
        markerStore[markerKey("u1")] = "mom"

        migrator.reslotIfSlotChanged(myUid = "u1", newRole = "mom")

        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 0) { eventDao.reslotPickup(any(), any(), any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
    }

    @Test
    fun `the first profile this device has ever seen is not treated as a change`() = runTest {
        // No marker seeded: `markerStore` starts empty for this uid, matching a device that
        // has never reacted to a slot for it before — the real "no history" fact, tracked
        // independently of whatever placeholder Room's own `role` field might already hold.
        //
        // A real active pattern is stubbed in on purpose: if the missing-marker guard were
        // dropped, "absent -> dad" would read as a flip and this pattern would be
        // complemented and saved, inverting a schedule that just arrived, correctly, from
        // the co-parent's shared document. Without this stub the test would pass even with
        // the guard removed, because there would be nothing for a dropped guard to wrongly
        // act on.
        coEvery { custodyModelRepository.getActiveModelSync() } returns custodyModel(setOf(0, 1))

        migrator.reslotIfSlotChanged(myUid = "u1", newRole = "dad")

        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 0) { eventDao.reslotPickup(any(), any(), any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
        // Seeded so the *next* call has a real baseline instead of staying empty forever.
        assertEquals("dad", markerStore[markerKey("u1")])
    }

    @Test
    fun `a slot change complements the active custody model`() = runTest {
        markerStore[markerKey("u1")] = "mom"
        val active = custodyModel(momDays = setOf(0, 1, 2))
        coEvery { custodyModelRepository.getActiveModelSync() } returns active
        val saved = slot<CustodyModel>()
        coEvery { custodyModelRepository.saveReslotted(capture(saved)) } returns Unit

        migrator.reslotIfSlotChanged(myUid = "u1", newRole = "dad")

        assertEquals(active.complemented().momDayIndices, saved.captured.momDayIndices)
        // Structurally guaranteed by the code (nothing on this path can reach
        // saveAndActivate), pinned anyway so an edit that adds a Firestore push here fails a
        // test instead of quietly passing one.
        coVerify(exactly = 0) { custodyModelRepository.saveAndActivate(any()) }
    }

    @Test
    fun `a slot change with no active custody model is not an error`() = runTest {
        markerStore[markerKey("u1")] = "mom"
        coEvery { custodyModelRepository.getActiveModelSync() } returns null

        val failure = runCatching {
            migrator.reslotIfSlotChanged(myUid = "u1", newRole = "dad")
        }

        assertNull(failure.exceptionOrNull())
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
    }

    // ---- regressions for the round-1 review's three Critical findings --------------------

    @Test
    fun `reslot already advances the marker, so a sync right after an Accept sees no change`() = runTest {
        // Critical 1: PairingViewModel.withSlotReslot re-stamps by calling `reslot` directly —
        // never `reslotIfSlotChanged` — and Room's `role` is never written by the accept path
        // at all. A sync landing within the following fifteen minutes must not read the slot
        // that already changed as a second, brand-new transition and complement an
        // already-correct custody model a second time, inverting it.
        coEvery { eventDao.reslotOwner(any(), any(), any()) } returns 3
        coEvery { eventDao.reslotPickup(any(), any(), any()) } returns 1
        coEvery { custodyModelRepository.getActiveModelSync() } returns custodyModel(setOf(0, 1))

        migrator.reslot(from = "mom", to = "dad", myUid = "u1") // the accept path's own call
        migrator.reslotIfSlotChanged(myUid = "u1", newRole = "dad") // the sync that follows

        coVerify(exactly = 1) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 1) { eventDao.reslotPickup(any(), any(), any()) }
        coVerify(exactly = 0) { custodyModelRepository.saveReslotted(any()) }
    }

    @Test
    fun `a reslot that fails to complete leaves the marker stale so the next sync retries`() = runTest {
        // Critical 3: the marker must not advance unless the re-stamp actually committed, or
        // an interrupted pass would be indistinguishable, on the next sync, from one that ran
        // to completion — the transition would be lost permanently instead of retried.
        markerStore[markerKey("u1")] = "mom"
        coEvery { eventDao.reslotOwner(any(), any(), any()) } throws IllegalStateException("boom")

        runCatching { migrator.reslotIfSlotChanged(myUid = "u1", newRole = "dad") }

        assertEquals("mom", markerStore[markerKey("u1")])
    }

    private fun custodyModel(momDays: Set<Int>) = CustodyModel(
        id = "pattern-1",
        modelType = CustodyModelType.CUSTOM,
        patternDays = 7,
        momDayIndices = momDays,
        startDate = LocalDate.of(2026, 8, 1)
    )
}
