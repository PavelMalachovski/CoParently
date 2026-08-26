package com.coparently.app.presentation.calendar

/** The fill a day cell starts from, before anything is drawn over it. */
enum class DayCellBase {
    /** The screen's own surface. */
    SURFACE,

    /** Saturday or Sunday — a neutral grey, applied in every grid row. */
    WEEKEND
}

/** What is drawn over [DayCellBase], at its own alpha, or nothing. */
enum class DayCellOverlay {
    NONE,
    CUSTODY_MOM,
    CUSTODY_DAD,
    PUBLIC_HOLIDAY,

    /** Week view only; the month grid marks today on the day number instead. */
    TODAY
}

/**
 * A day cell's fills: the [base] it starts from, the [overlay] drawn over it, and — on a day the
 * child changes hands — the parent the day is coming *from*.
 *
 * @property base Always decided by the weekday alone, so the weekend band is continuous.
 * @property overlay The cell's meaning — custody, holiday or today — or [DayCellOverlay.NONE].
 * @property handoverFrom The parent who had the child *yesterday*, when the child changes hands
 *   on this cell's morning; null on every other day. Only ever [DayCellOverlay.CUSTODY_MOM] or
 *   [DayCellOverlay.CUSTODY_DAD]. It is a **shape**, not a fourth colour: the cell is split on a
 *   diagonal with this parent in the top-left and [overlay]'s parent in the bottom-right, so the
 *   weekend base still shows through both halves and this file's invariant survives. Reserved
 *   for **pattern** boundaries: a day decided by an accepted one-off swap (and the day after it)
 *   renders as a single solid fill instead — an owner decision from the Aug 2026 walkthrough,
 *   where the split read as "the swap only half-happened".
 * @property pendingProposalFor The parent a **pending, unanswered** custody proposal would give
 *   this day to, when that differs from whose day it is now; null on every other day. Only ever
 *   [DayCellOverlay.CUSTODY_MOM] or [DayCellOverlay.CUSTODY_DAD]. It is drawn *over* [overlay],
 *   at a lower alpha, so the agreed pattern keeps its full strength underneath: a proposal is a
 *   preview, and a grid that showed only the proposal would tell a parent their days had changed
 *   when they had not. The two translucent hues do blend into something that is neither parent's
 *   colour, and that is the intended reading — the day is being argued about. The cell's spoken
 *   description says so in words, which is what actually disambiguates it.
 * @property isAdjacentMonth Whether this cell is one of the leading or trailing days borrowed
 *   from a neighbouring month. It is a **fact about the day, not a colour**: the caller draws the
 *   same fills at a reduced strength, the way the day number and the event dots are already
 *   dimmed. Encoded here rather than left to the view so that every consequence of a day being
 *   borrowed is decided in this file, which is the whole point of it.
 */
data class DayCellFill(
    val base: DayCellBase,
    val overlay: DayCellOverlay,
    val handoverFrom: DayCellOverlay? = null,
    val pendingProposalFor: DayCellOverlay? = null,
    val isAdjacentMonth: Boolean = false
)

/**
 * Decides what a calendar day cell is filled with, kept out of Compose so the branching can be
 * unit tested without a composition.
 *
 * **The weekend is a base, not a competitor.** The month cell used to pick exactly one
 * background, with custody ahead of the weekend; because
 * [com.coparently.app.domain.model.CustodyModel.getCustodyFor] reduces any date into the pattern
 * cycle and always answers `"mom"` or `"dad"`, every in-month cell matched a custody branch and
 * the weekend branch could not be reached at all. The only weekends that kept a tint were the
 * ones belonging to a neighbouring month, which is why the band appeared in some grid rows and
 * not others with no rule a reader could infer.
 *
 * Weekend deliberately does **not** win over custody: weekends are the days a separated parent
 * checks first, and replacing the parent hue there with grey would remove the answer from exactly
 * the cells the screen exists to give it in.
 *
 * **Every case a cell can be in is decided here, in one place**: weekend base, custody overlay,
 * public holiday, today, the handover diagonal and the pending-proposal preview. Two functions
 * that each knew some of the cases is precisely how the weekend band became unreachable, so a new
 * case folds in rather than growing a second decision beside this one. (Pending *swap* arrows are
 * the deliberate exception: they are a glyph, not a fill, and `MonthView` draws them from a set of
 * dates — a swap changes nothing about whose day it is, so it has no business in a colour.)
 */
object DayCellFills {

    /**
     * The fill for a cell in the month grid.
     *
     * @param isWeekend Saturday or Sunday.
     * @param isCurrentMonth False for the leading and trailing days borrowed from the
     *   neighbouring months. **They still carry the custody band**, at the reduced strength
     *   [DayCellFill.isAdjacentMonth] asks the caller for. They used to carry nothing at all, and
     *   a band that stops in the middle of a grid row is the same defect as a weekend band that
     *   appears in some rows and not others: custody is a *pattern*, and a reader cannot infer a
     *   pattern from a row that goes blank halfway across. What they still do not take is
     *   anything they cannot be acted on for — the holiday tint, the proposal preview, the swap
     *   arrows — because those mark a day you would do something about, and a borrowed cell
     *   refuses every gesture.
     * @param custody `"mom"`, `"dad"`, or null when no custody model or legacy schedule applies.
     *   Any other value is treated as no custody rather than guessed at.
     * @param previousCustody Whose day *yesterday* was, resolved through the same lookup. When it
     *   differs from [custody] the child changes hands on this cell's morning, and the cell is
     *   split diagonally — see [DayCellFill.handoverFrom].
     * @param isPublicHoliday A public holiday, not a school vacation — school vacation is a
     *   month-level banner and never a cell fill.
     * @param proposedCustody Whose day this would be under a **pending** custody proposal, or
     *   null when nothing is pending. Resolved from the proposal's own pattern, never from the
     *   agreed one. See [pendingProposal].
     * @param isSwapped Whether an **accepted** one-off swap decides this day. A swapped day is a
     *   whole day in the other parent's care, so it renders as one solid fill — the diagonal
     *   would say the swap only half-happened. The custody lookup already resolves *whose* day it
     *   is; this flag only says the answer came from a swap rather than the pattern.
     * @param previousSwapped Whether an accepted swap decides *yesterday*. Suppresses the split
     *   this cell would otherwise inherit from the swap's far edge, for the same reason.
     */
    @Suppress("LongParameterList") // one cell's inputs, expressed as one parameter list
    fun monthCell(
        isWeekend: Boolean,
        isCurrentMonth: Boolean,
        custody: String?,
        previousCustody: String?,
        isPublicHoliday: Boolean,
        proposedCustody: String? = null,
        isSwapped: Boolean = false,
        previousSwapped: Boolean = false
    ): DayCellFill = DayCellFill(
        base = baseFor(isWeekend),
        // The holiday tint is the one overlay the borrowed days give up: it marks a single day,
        // and a single day missing from a borrowed cell breaks no pattern. The custody band is a
        // pattern, so it runs to the edge of the grid.
        overlay = custodyOverlay(custody)
            ?: if (isPublicHoliday && isCurrentMonth) {
                DayCellOverlay.PUBLIC_HOLIDAY
            } else {
                DayCellOverlay.NONE
            },
        // The diagonal travels with the band, on a borrowed day too. Drawing the band but not the
        // handover would put the change of hands a day late — wrong information, where leaving
        // the whole cell blank was merely silent.
        handoverFrom = if (isSwapped || previousSwapped) {
            null
        } else {
            handoverFrom(custody, previousCustody)
        },
        pendingProposalFor = if (isCurrentMonth) pendingProposal(custody, proposedCustody) else null,
        isAdjacentMonth = !isCurrentMonth
    )

    /**
     * The parent a handover on this day comes from, or null when there is no handover.
     *
     * Both days must resolve to something. An unanswered day is not a handover, it is an unknown,
     * and splitting the cell for it would invent an arrangement neither parent agreed to — the
     * same rule `CustodyResolver.isHandoverDay` applies, kept in step with it deliberately.
     */
    private fun handoverFrom(custody: String?, previousCustody: String?): DayCellOverlay? {
        if (custody == null || previousCustody == null || custody == previousCustody) return null
        return custodyOverlay(previousCustody)
    }

    /**
     * The parent a pending proposal would move this day to, or null when the day is not moving.
     *
     * Null when the proposal agrees with the agreed pattern, which is most of the grid: a
     * proposal is a whole pattern, so marking every day it covers would wash the month and say
     * nothing. Only the days that would actually change are drawn as a preview.
     *
     * Null when either side is unknown, for the same reason [handoverFrom] refuses one: an
     * unanswered day is not a change, it is an unknown, and previewing a move away from nothing
     * would invent the arrangement it claims to be previewing.
     */
    private fun pendingProposal(custody: String?, proposedCustody: String?): DayCellOverlay? {
        if (custody == null || proposedCustody == null || custody == proposedCustody) return null
        return custodyOverlay(proposedCustody)
    }

    /**
     * The fill for one hour cell in the week or day view.
     *
     * Custody beats today for the same reason it does in the month grid: today already reads as
     * today from its coloured column header, and the old order hid custody on the one column
     * parents check first.
     *
     * @param isWeekend Saturday or Sunday.
     * @param isToday Whether this column is today.
     * @param custody `"mom"`, `"dad"`, or null.
     * @param proposedCustody Whose day this would be under a pending proposal, or null when
     *   nothing is pending. The week view gets the same preview as the month grid: unlike the
     *   handover diagonal and the swap arrows — which package C kept to the month, because the
     *   week already shows a handover as the boundary between two runs of its custody band — a
     *   pending proposal has no other form in this layout. Leaving it out would make the week
     *   the one view that shows a schedule as settled while the month says it is not.
     */
    fun weekHourCell(
        isWeekend: Boolean,
        isToday: Boolean,
        custody: String?,
        proposedCustody: String? = null
    ): DayCellFill =
        DayCellFill(
            base = baseFor(isWeekend),
            overlay = custodyOverlay(custody)
                ?: if (isToday) DayCellOverlay.TODAY else DayCellOverlay.NONE,
            pendingProposalFor = pendingProposal(custody, proposedCustody)
        )

    private fun baseFor(isWeekend: Boolean) =
        if (isWeekend) DayCellBase.WEEKEND else DayCellBase.SURFACE

    private fun custodyOverlay(custody: String?): DayCellOverlay? = when (custody) {
        "mom" -> DayCellOverlay.CUSTODY_MOM
        "dad" -> DayCellOverlay.CUSTODY_DAD
        else -> null
    }
}
