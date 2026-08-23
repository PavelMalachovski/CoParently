package com.coparently.app.domain.events

import com.coparently.app.domain.model.Event
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an event needs the other parent's word, and who is allowed to give it.
 *
 * Two cases here are about data loss rather than etiquette. A private event must never be marked
 * pending: it never reaches Firestore, so the co-parent could not answer it even in principle, and
 * a pending event is hidden from every calendar view — its own creator would lose it permanently,
 * with nobody able to give it back. And an event created while unpaired must not be pending
 * either, for the same reason with a different cause.
 */
class EventAcceptanceTransitionTest {

    private val me = "uid-mom"
    private val them = "uid-dad"
    private val at = LocalDateTime.of(2026, 9, 1, 10, 0)

    private fun event(
        parentOwner: String = "dad",
        createdBy: String? = "uid-mom",
        isPrivate: Boolean = false,
        acceptance: EventAcceptance = EventAcceptance.NOT_REQUIRED
    ) = Event(
        id = "e1",
        title = "Football",
        startDateTime = at,
        eventType = "training",
        parentOwner = parentOwner,
        createdAt = at,
        updatedAt = at,
        createdByFirebaseUid = createdBy,
        isPrivate = isPrivate,
        acceptance = acceptance
    )

    @Test
    fun `an event created for the co-parent needs their word`() {
        assertTrue(
            EventAcceptanceTransition.required(
                event = event(parentOwner = "dad"),
                creatorSlot = "mom",
                partnerUid = them
            )
        )
    }

    @Test
    fun `an event a parent creates for themselves does not`() {
        assertFalse(
            EventAcceptanceTransition.required(
                event = event(parentOwner = "mom"),
                creatorSlot = "mom",
                partnerUid = them
            )
        )
    }

    @Test
    fun `an event created while unpaired does not, because nobody could answer it`() {
        assertFalse(
            EventAcceptanceTransition.required(
                event = event(parentOwner = "dad"),
                creatorSlot = "mom",
                partnerUid = null
            )
        )
    }

    @Test
    fun `a private event never needs acceptance, because it never reaches the co-parent`() {
        // A pending event is hidden from every calendar view and a private one never reaches
        // Firestore, so marking one pending would delete it from its own creator's calendar with
        // nobody able to give it back.
        assertFalse(
            EventAcceptanceTransition.required(
                event = event(parentOwner = "dad", isPrivate = true),
                creatorSlot = "mom",
                partnerUid = them
            )
        )
    }

    @Test
    fun `an unknown creator slot means no acceptance rather than a guess`() {
        assertFalse(
            EventAcceptanceTransition.required(
                event = event(parentOwner = "dad"),
                creatorSlot = null,
                partnerUid = them
            )
        )
    }

    @Test
    fun `the recipient accepts, and the event records who and when`() {
        val pending = event(acceptance = EventAcceptance.PENDING)

        val accepted = EventAcceptanceTransition.accept(pending, them, at).getOrThrow()

        assertEquals(EventAcceptance.ACCEPTED, accepted.acceptance)
        assertEquals(them, accepted.acceptedBy)
        assertEquals(at, accepted.acceptedAt)
    }

    @Test
    fun `the creator may not accept their own event into the other parent's calendar`() {
        val pending = event(acceptance = EventAcceptance.PENDING)

        assertTrue(EventAcceptanceTransition.accept(pending, me, at).isFailure)
    }

    @Test
    fun `declining leaves the event, and says so`() {
        val pending = event(acceptance = EventAcceptance.PENDING)

        val declined = EventAcceptanceTransition.decline(pending, them, at).getOrThrow()

        assertEquals(EventAcceptance.DECLINED, declined.acceptance)
        assertEquals("e1", declined.id)
        assertEquals("Football", declined.title)
    }

    @Test
    fun `a decided event cannot be decided again`() {
        val accepted = event(acceptance = EventAcceptance.ACCEPTED)

        assertTrue(EventAcceptanceTransition.accept(accepted, them, at).isFailure)
        assertTrue(EventAcceptanceTransition.decline(accepted, them, at).isFailure)
    }

    @Test
    fun `an event nobody has to agree to cannot be accepted either`() {
        val ordinary = event(acceptance = EventAcceptance.NOT_REQUIRED)

        assertTrue(EventAcceptanceTransition.accept(ordinary, them, at).isFailure)
    }

    @Test
    fun `the creator may withdraw a pending event, and only the creator`() {
        val pending = event(acceptance = EventAcceptance.PENDING)

        assertTrue(EventAcceptanceTransition.cancel(pending, me).isSuccess)
        assertTrue(EventAcceptanceTransition.cancel(pending, them).isFailure)
    }

    @Test
    fun `an already-answered event cannot be withdrawn out from under the answer`() {
        val accepted = event(acceptance = EventAcceptance.ACCEPTED)

        assertTrue(EventAcceptanceTransition.cancel(accepted, me).isFailure)
    }
}
