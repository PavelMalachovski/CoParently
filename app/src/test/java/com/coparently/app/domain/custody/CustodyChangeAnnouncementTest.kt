package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [CustodyChangeAnnouncement].
 *
 * Custody is last-write-wins with no consent step, so the one thing this decision must get
 * right is that a losing write is never silently swallowed: it has to surface as "the other
 * parent changed it" unless it demonstrably is not that.
 */
class CustodyChangeAnnouncementTest {

    @Test
    fun `a remote change by the co-parent is announced`() {
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            dismissedLastModifiedAt = null
        )

        assertEquals(shared, result)
    }

    @Test
    fun `my own write is not announced`() {
        val shared = custodyOf(lastModifiedBy = MY_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            dismissedLastModifiedAt = null
        )

        assertNull(result)
    }

    @Test
    fun `a dismissed lastModifiedAt stays dismissed`() {
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID, lastModifiedAt = MODIFIED_AT)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            dismissedLastModifiedAt = MODIFIED_AT
        )

        assertNull(result)
    }

    @Test
    fun `the next change with a different lastModifiedAt is announced again`() {
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID, lastModifiedAt = "2026-08-06T10:00:00")

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            dismissedLastModifiedAt = "2026-08-05T09:00:00"
        )

        assertEquals(shared, result)
    }

    @Test
    fun `a lastModifiedBy matching neither parent is still announced`() {
        // Naming falls back to "unknown parent" for a uid like this one — that is the caller's
        // job (uid to slot to parentLabel). This decision only answers "is there a change to
        // announce", and a stranger uid is not a reason to suppress a real change.
        val shared = custodyOf(lastModifiedBy = "some-stranger-uid")

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            dismissedLastModifiedAt = null
        )

        assertEquals(shared, result)
    }

    @Test
    fun `nothing shared yet is not announced`() {
        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = null,
            myUid = MY_UID,
            dismissedLastModifiedAt = null
        )

        assertNull(result)
    }

    @Test
    fun `an unknown own uid does not suppress a real remote change`() {
        // Before Parents has loaded, myUid is null. A change must not be misread as "mine"
        // merely because this device does not yet know its own uid.
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = null,
            dismissedLastModifiedAt = null
        )

        assertEquals(shared, result)
    }

    private fun custodyOf(
        lastModifiedBy: String,
        lastModifiedAt: String = MODIFIED_AT
    ) = SharedCustody(
        model = CustodyModel(
            id = "model-1",
            modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
            patternDays = 14,
            momDayIndices = (0..6).toSet(),
            startDate = LocalDate.of(2026, 1, 1)
        ),
        lastModifiedBy = lastModifiedBy,
        lastModifiedAt = lastModifiedAt,
        createdAt = "2026-01-01T00:00:00"
    )

    private companion object {
        const val MY_UID = "my-uid"
        const val CO_PARENT_UID = "co-parent-uid"
        const val MODIFIED_AT = "2026-08-05T12:00:00"
    }
}
