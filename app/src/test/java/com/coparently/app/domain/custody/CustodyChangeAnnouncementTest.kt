package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import org.junit.Test
import java.time.Instant
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
            dismissedLastModifiedAtMillis = null
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
            dismissedLastModifiedAtMillis = null
        )

        assertNull(result)
    }

    @Test
    fun `a dismissed change stays dismissed`() {
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAtMillis = MODIFIED_AT_MILLIS
        )

        assertNull(result)
    }

    @Test
    fun `the next change at a different instant is announced again`() {
        val shared = custodyOf(
            lastModifiedBy = CO_PARENT_UID,
            lastModifiedAtMillis = MODIFIED_AT_MILLIS + 1
        )

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAtMillis = MODIFIED_AT_MILLIS
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
            dismissedLastModifiedAtMillis = null
        )

        assertEquals(shared, result)
    }

    @Test
    fun `nothing shared yet is not announced`() {
        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = null,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAtMillis = null
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
            dismissedLastModifiedAtMillis = null
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
            dismissedLastModifiedAtMillis = null
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
            dismissedLastModifiedAtMillis = null
        )

        assertEquals(shared, result)
    }

    @Test
    fun `a one-off swap is not announced as a schedule change`() {
        // `firestore.rules` requires every update to stamp `lastModifiedBy` with its caller, so a
        // swap write cannot leave the field alone. Without the kind marker this is
        // indistinguishable from the co-parent rewriting the agreed pattern, and the other phone
        // would announce a schedule change for a day nobody has agreed to.
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID, kind = CustodyWriteKind.SWAP)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAtMillis = null
        )

        assertNull(result)
    }

    @Test
    fun `a pattern change after a swap is still announced`() {
        // The marker is per-write, not sticky: the next real pattern change must come through.
        val shared = custodyOf(lastModifiedBy = CO_PARENT_UID, kind = CustodyWriteKind.PATTERN)

        val result = CustodyChangeAnnouncement.toAnnounce(
            shared = shared,
            myUid = MY_UID,
            parentsLoaded = true,
            dismissedLastModifiedAtMillis = null
        )

        assertEquals(shared, result)
    }

    private fun custodyOf(
        lastModifiedBy: String,
        lastModifiedAtMillis: Long = MODIFIED_AT_MILLIS,
        kind: CustodyWriteKind = CustodyWriteKind.PATTERN
    ) = SharedCustody(
        model = CustodyModel(
            id = "model-1",
            modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
            patternDays = 14,
            momDayIndices = (0..6).toSet(),
            startDate = LocalDate.of(2026, 1, 1)
        ),
        lastModifiedBy = lastModifiedBy,
        lastModifiedAtMillis = lastModifiedAtMillis,
        lastModifiedAt = "2026-08-05T12:00:00",
        createdAt = "2026-01-01T00:00:00",
        lastModifiedKind = kind
    )

    private companion object {
        const val MY_UID = "my-uid"
        const val CO_PARENT_UID = "co-parent-uid"
        val MODIFIED_AT_MILLIS: Long = Instant.parse("2026-08-05T10:00:00Z").toEpochMilli()
    }
}
