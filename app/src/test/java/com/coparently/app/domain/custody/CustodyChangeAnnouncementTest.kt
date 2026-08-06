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
 * parent changed it" unless it demonstrably is not that — and the reverse failure is just as
 * real: it must never report this device's own write as the co-parent's.
 */
class CustodyChangeAnnouncementTest {

    @Test
    fun `a remote change by the co-parent is announced`() {
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
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
            parentsLoaded = true,
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
            parentsLoaded = true,
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
            parentsLoaded = true,
            dismissedLastModifiedAt = "2026-08-05T09:00:00"
        )

        assertEquals(shared, result)
    }

    @Test
    fun `a lastModifiedBy matching neither parent is still announced`() {
        // Naming falls back to "unknown parent" for a uid like this one — that is the caller's
        // job (parentLabelByUid, resolved directly against the uid, never through a slot). This
        // decision only answers "is there a change to announce", and a stranger uid is not a
        // reason to suppress a real change.
        val shared = custodyOf(lastModifiedBy = "some-stranger-uid")

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAt = null
        )

        assertEquals(shared, result)
    }

    @Test
    fun `nothing shared yet is not announced`() {
        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = null,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAt = null
        )

        assertNull(result)
    }

    @Test
    fun `an account with no Room profile row never suppresses a real remote change`() {
        // Parents.me can be null forever for an account with no local profile row - loaded is
        // still true, and there is nothing more to wait for. A null myUid must not be read as
        // "this could be my write" once parents has actually loaded.
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = null,
            parentsLoaded = true,
            dismissedLastModifiedAt = null
        )

        assertEquals(shared, result)
    }

    @Test
    fun `nothing is announced before parents has loaded, even for a genuine remote change`() {
        // Parents starts every fresh subscription from a synthetic "nobody is known yet", and
        // CustodyModelRepository's pair resolution (Room-only) is reliably faster than Parents
        // resolving three Firestore pairing listeners. So the echo of this device's own
        // just-made write can arrive before myUid is known - treating "not loaded" as "definitely
        // not mine" would announce the user's own edit as the co-parent's.
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = null,
            parentsLoaded = false,
            dismissedLastModifiedAt = null
        )

        assertNull(result)
    }

    @Test
    fun `the same change is announced once parents resolves`() {
        // The other half of the test above: once parents has produced a real answer, the change
        // that was withheld while unloaded is announced - nothing is lost, only delayed.
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
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
