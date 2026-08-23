package com.coparently.app.domain.events

import com.coparently.app.domain.model.Event
import java.time.LocalDateTime

/**
 * Who may say yes to an event created for them, and when the question is asked at all.
 *
 * Pure functions over an [Event], returning a new one — the same shape
 * `CustodyProposalTransition` and `DayOverrideTransition` use, and for the same reason: the rule
 * that makes this an agreement rather than an imposition is testable without Firestore, Room or a
 * coroutine.
 *
 * Every function returns a [Result] rather than throwing or silently no-oping. A refused
 * transition is a real outcome the UI has to show, and a silent no-op is the invisible change this
 * whole family of features exists to remove.
 */
object EventAcceptanceTransition {

    /**
     * Whether [event] should be created [EventAcceptance.PENDING] rather than
     * [EventAcceptance.NOT_REQUIRED].
     *
     * True only when all of these hold: the account is paired, the event is owned by the *other*
     * parent's slot, and the event is not private.
     *
     * The last two are not symmetry, they are safety. A pending event is hidden from every
     * calendar view, so anything marked pending that the co-parent cannot actually answer is
     * simply gone:
     *
     * - **A private event never reaches Firestore** — `EventRepositoryImpl` and `SyncService` both
     *   filter it — so the co-parent could not answer it even in principle. Its creator would lose
     *   it from their own calendar with nobody able to give it back.
     * - **An unpaired account has nobody to ask.** Same outcome, different cause.
     *
     * A null [creatorSlot] answers false rather than guessing. The slot decides whose event this
     * is, and guessing it wrong in this direction hides an event; guessing it wrong in the other
     * merely skips a question.
     *
     * @param event The event about to be created.
     * @param creatorSlot The creating parent's own slot — `"mom"` or `"dad"`. The uid cannot
     *   answer this: [Event.parentOwner] is a slot, and only the creator's own slot says whether
     *   the event is for them or for the other parent.
     * @param partnerUid The co-parent's uid, or null/blank when unpaired.
     */
    fun required(event: Event, creatorSlot: String?, partnerUid: String?): Boolean {
        if (partnerUid.isNullOrBlank()) return false
        if (event.isPrivate) return false
        if (creatorSlot.isNullOrBlank()) return false
        return event.parentOwner != creatorSlot
    }

    /**
     * Takes up an event created for this parent. From here it behaves like any other event.
     *
     * The creator may not accept on the recipient's behalf — that is precisely the unilateral
     * addition this feature replaces, arriving through the new door instead of the old one.
     */
    fun accept(event: Event, byUid: String?, at: LocalDateTime): Result<Event> =
        event.decidableBy(byUid).map {
            it.copy(
                acceptance = EventAcceptance.ACCEPTED,
                acceptedBy = byUid,
                acceptedAt = at,
                updatedAt = at,
                lastModifiedBy = byUid,
                syncedToFirestore = false
            )
        }

    /**
     * Turns an event down. It stays [EventAcceptance.DECLINED] rather than being deleted: the
     * creator needs to know the answer was no, and an event that silently vanished would read as
     * a bug.
     */
    fun decline(event: Event, byUid: String?, at: LocalDateTime): Result<Event> =
        event.decidableBy(byUid).map {
            it.copy(
                acceptance = EventAcceptance.DECLINED,
                acceptedBy = byUid,
                acceptedAt = at,
                updatedAt = at,
                lastModifiedBy = byUid,
                syncedToFirestore = false
            )
        }

    /**
     * Authorises the creator to withdraw an event still waiting for an answer.
     *
     * **Withdrawing deletes the row**, and the returned [Event] is the row to delete — this
     * function exists to answer "may they?", and to carry the refusal reason when they may not.
     * There is no `WITHDRAWN` status because there is nothing for one to mean: the recipient never
     * agreed to the event, so nothing about it is worth keeping, and a fifth enum member would
     * have to be filtered out of every view that already filters the other two.
     *
     * An already-answered event cannot be withdrawn. Deleting it would erase the co-parent's
     * answer from under them, which is the silent change this package exists to prevent.
     */
    fun cancel(event: Event, byUid: String?): Result<Event> {
        if (!event.acceptance.isPending) {
            return Result.failure(
                IllegalStateException("This event is not waiting for an answer")
            )
        }
        return if (event.createdByFirebaseUid != null && event.createdByFirebaseUid == byUid) {
            Result.success(event)
        } else {
            Result.failure(
                IllegalStateException("Only the parent who created it may withdraw it")
            )
        }
    }

    /**
     * This event, if [uid] is entitled to answer it — which its creator never is, and which
     * nobody is once it has been answered or was never in question.
     */
    private fun Event.decidableBy(uid: String?): Result<Event> {
        if (!acceptance.isPending) {
            return Result.failure(
                IllegalStateException("This event is not waiting for an answer")
            )
        }
        if (uid.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No signed-in parent to answer with"))
        }
        return if (createdByFirebaseUid == uid) {
            Result.failure(
                IllegalStateException("A parent may not accept their own event for the other")
            )
        } else {
            Result.success(this)
        }
    }
}
