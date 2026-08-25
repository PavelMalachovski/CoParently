package com.coparently.app.domain.custody

/**
 * The three things that can happen to a one-off day swap, as pure functions over the whole
 * `date -> `[DayOverride] map.
 *
 * The same shape as [CustodyProposalTransition] at a smaller scale, and matching it is the point:
 * both answer "may this parent do this?" without Firestore, Room or a coroutine, so the rule that
 * makes a swap an *agreement* rather than an announcement can be tested on the JVM.
 *
 * Every function returns a [Result] rather than throwing or silently no-oping. A refused
 * transition is a real outcome the UI has to show, and a silent no-op is the invisible change
 * this whole family of features exists to remove.
 *
 * **A swap write leaves the pattern and `SharedCustody.lastModifiedAt` exactly as they were.**
 * That is not tidiness: `lastModifiedAt` is what `CustodyModelRepository.isNewer` uses to decide
 * which phone's document survives — a comparison that then *re-pushes the winner over the loser*
 * — so re-dating the document for a swap would make this device win every future comparison.
 *
 * `lastModifiedBy` is the one field a swap cannot leave alone: `firestore.rules` requires every
 * update to stamp it with the caller. That is why the document also carries
 * [SharedCustody.lastModifiedKind] — `CustodyChangeAnnouncement` reads `lastModifiedBy` to decide
 * whether the co-parent changed the agreed schedule, and without the marker every offer and every
 * answer would raise that banner about a day nobody has agreed to.
 */
object DayOverrideTransition {

    /**
     * Offers [date] to [toParent].
     *
     * An existing entry for the same date is **replaced**, not queued behind. One override per
     * date is the whole semantics, and the newest offer is the live one — including when the
     * co-parent had a pending offer for that same date, because two people negotiating one day
     * are talking about the same day and the later word is the current one. Every other date is
     * left untouched.
     *
     * Deliberately does not refuse a date in the past. Whether a swap of a day already lived is
     * offerable is a UI question — the sheet offers only today and later — and encoding "today"
     * here would give this pure function a clock.
     *
     * @param current The override map as it stands; absent from the document reads as empty.
     * @param date ISO date (`2026-09-05`) of the day being offered.
     * @param toParent The slot that would take the day — `"mom"` or `"dad"`.
     * @param byUid The parent offering — this device's own uid.
     * @param atIso ISO date-time of the offer.
     * @param note Optional free text; blank normalises to null.
     */
    @Suppress("LongParameterList") // one entry, one parameter per stored field
    fun offer(
        current: Map<String, DayOverride>,
        date: String,
        toParent: String,
        byUid: String,
        atIso: String,
        note: String? = null
    ): Result<Map<String, DayOverride>> = Result.success(
        current + (
            date to DayOverride(
                toParent = toParent,
                requestedBy = byUid,
                requestedAt = atIso,
                status = DayOverrideStatus.PENDING,
                note = note?.takeIf { it.isNotBlank() }
            )
            )
    )

    /**
     * Offers a run of days as one group.
     *
     * Every date is still its own entry — the map's one-override-per-date semantics is what makes
     * "we swap next Saturday" unambiguous, and a range field would quietly break it. What ties
     * them together is [groupId], which exists so the co-parent is asked once about five days
     * rather than five times about one.
     *
     * @param current The override map as it stands.
     * @param toParentByDate ISO date to the slot that would take it. Per date rather than one
     *   slot for the run: a selection that spans a handover flips each day to its complement, so
     *   the two halves of it go in opposite directions, and a single `toParent` would hand the
     *   whole run to one parent — a different agreement from the one the sheet showed.
     * @param byUid The parent offering.
     * @param atIso ISO date-time of the offer, the same instant for every day in the group.
     * @param groupId Ties the entries together. The caller generates it.
     * @param note Optional free text, applied to every day in the group.
     */
    @Suppress("LongParameterList") // one entry, one parameter per stored field
    fun offerAll(
        current: Map<String, DayOverride>,
        toParentByDate: Map<String, String>,
        byUid: String,
        atIso: String,
        groupId: String,
        note: String? = null
    ): Result<Map<String, DayOverride>> {
        if (toParentByDate.isEmpty()) {
            return Result.failure(IllegalArgumentException("A swap needs at least one day"))
        }
        val entries = toParentByDate.mapValues { (_, toParent) ->
            DayOverride(
                toParent = toParent,
                requestedBy = byUid,
                requestedAt = atIso,
                status = DayOverrideStatus.PENDING,
                note = note?.takeIf { it.isNotBlank() },
                groupId = groupId
            )
        }
        return Result.success(current + entries)
    }

    /**
     * Whether [date] in [current] is an offer [byUid] may still answer.
     *
     * The predicate [decideGroup] filters on, exposed because a caller that writes a group one
     * day at a time has to make the same judgement before it calls in — and has to make it
     * against the map the write just read, never against a mirror that can be behind.
     *
     * @param current The overrides as the document holds them.
     * @param date ISO date to test.
     * @param byUid The parent who would be answering.
     */
    fun awaitsAnswerFrom(current: Map<String, DayOverride>, date: String, byUid: String): Boolean =
        current[date]?.let { it.isPending && it.requestedBy != byUid } == true

    /**
     * Answers every still-pending day of one group at once.
     *
     * All or nothing was the owner's decision: one notification, one answer. A day of the group
     * that is already decided — the co-parent withdrew it, or an older build answered it singly
     * — is skipped rather than refused, so a part-answered group can still be finished.
     *
     * @return the new map, or a failure when the group holds nothing this parent may answer.
     */
    fun decideGroup(
        current: Map<String, DayOverride>,
        dates: Collection<String>,
        byUid: String,
        atIso: String,
        accept: Boolean
    ): Result<Map<String, DayOverride>> {
        val answerable = dates.filter { awaitsAnswerFrom(current, it, byUid) }
        if (answerable.isEmpty()) {
            return Result.failure(IllegalStateException("Nothing in this group is waiting on you"))
        }
        val outcome = if (accept) DayOverrideStatus.ACCEPTED else DayOverrideStatus.DECLINED
        var next = current
        answerable.forEach { date ->
            next = next.decide(date, byUid, atIso, note = null, outcome = outcome)
                .getOrElse { return Result.failure(it) }
        }
        return Result.success(next)
    }

    /**
     * Takes up the co-parent's offer of [date]. From here `CustodyResolver` reads the override
     * instead of the pattern.
     *
     * **Nothing folds an accepted swap back into the pattern**, which is what keeps it one-off:
     * no code path turns "we swapped one Saturday" into "Saturdays are yours now".
     */
    fun accept(
        current: Map<String, DayOverride>,
        date: String,
        byUid: String,
        atIso: String,
        note: String? = null
    ): Result<Map<String, DayOverride>> =
        current.decide(date, byUid, atIso, note, DayOverrideStatus.ACCEPTED)

    /**
     * Turns the co-parent's offer of [date] down. Whose day it is does not change — a refusal
     * changes nothing except that the parent who offered now knows.
     */
    fun decline(
        current: Map<String, DayOverride>,
        date: String,
        byUid: String,
        atIso: String,
        note: String? = null
    ): Result<Map<String, DayOverride>> =
        current.decide(date, byUid, atIso, note, DayOverrideStatus.DECLINED)

    /**
     * Answers a pending offer, refusing the two ways an answer can be illegitimate.
     *
     * A parent deciding their **own** offer is precisely the unilateral change this feature
     * replaces, arriving through a new door instead of the old one — `firestore.rules` refuses it
     * too, so a client that skipped this check would merely fail later and less clearly.
     * Re-deciding an already-answered offer is refused because the answer is the record the other
     * parent reads, and overwriting it would rewrite what they were told.
     */
    @Suppress("LongParameterList") // the caller's five inputs, plus which answer is being given
    private fun Map<String, DayOverride>.decide(
        date: String,
        byUid: String,
        atIso: String,
        note: String?,
        outcome: DayOverrideStatus
    ): Result<Map<String, DayOverride>> {
        val existing = this[date]
            ?: return Result.failure(IllegalStateException("No swap is pending for $date"))
        if (existing.requestedBy == byUid) {
            return Result.failure(
                IllegalStateException("A parent may not decide their own swap")
            )
        }
        if (!existing.isPending) {
            return Result.failure(
                IllegalStateException("The swap for $date has already been decided")
            )
        }
        return Result.success(
            this + (
                date to existing.copy(
                    status = outcome,
                    decidedBy = byUid,
                    decidedAt = atIso,
                    note = note?.takeIf { it.isNotBlank() } ?: existing.note
                )
                )
        )
    }
}
