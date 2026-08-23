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
 *   weekend base still shows through both halves and this file's invariant survives.
 */
data class DayCellFill(
    val base: DayCellBase,
    val overlay: DayCellOverlay,
    val handoverFrom: DayCellOverlay? = null
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
 */
object DayCellFills {

    /**
     * The fill for a cell in the month grid.
     *
     * @param isWeekend Saturday or Sunday.
     * @param isCurrentMonth False for the leading and trailing days borrowed from the
     *   neighbouring months. They take the base but never an overlay, matching their already
     *   dimmed day numbers.
     * @param custody `"mom"`, `"dad"`, or null when no custody model or legacy schedule applies.
     *   Any other value is treated as no custody rather than guessed at.
     * @param previousCustody Whose day *yesterday* was, resolved through the same lookup. When it
     *   differs from [custody] the child changes hands on this cell's morning, and the cell is
     *   split diagonally — see [DayCellFill.handoverFrom]. A one-off swap therefore both creates
     *   a split and removes the one it displaced, for free, because the lookup already accounts
     *   for it.
     * @param isPublicHoliday A public holiday, not a school vacation — school vacation is a
     *   month-level banner and never a cell fill.
     */
    fun monthCell(
        isWeekend: Boolean,
        isCurrentMonth: Boolean,
        custody: String?,
        previousCustody: String?,
        isPublicHoliday: Boolean
    ): DayCellFill = DayCellFill(
        base = baseFor(isWeekend),
        overlay = if (!isCurrentMonth) {
            DayCellOverlay.NONE
        } else {
            custodyOverlay(custody)
                ?: if (isPublicHoliday) DayCellOverlay.PUBLIC_HOLIDAY else DayCellOverlay.NONE
        },
        handoverFrom = if (isCurrentMonth) handoverFrom(custody, previousCustody) else null
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
     * The fill for one hour cell in the week or day view.
     *
     * Custody beats today for the same reason it does in the month grid: today already reads as
     * today from its coloured column header, and the old order hid custody on the one column
     * parents check first.
     *
     * @param isWeekend Saturday or Sunday.
     * @param isToday Whether this column is today.
     * @param custody `"mom"`, `"dad"`, or null.
     */
    fun weekHourCell(isWeekend: Boolean, isToday: Boolean, custody: String?): DayCellFill =
        DayCellFill(
            base = baseFor(isWeekend),
            overlay = custodyOverlay(custody)
                ?: if (isToday) DayCellOverlay.TODAY else DayCellOverlay.NONE
        )

    private fun baseFor(isWeekend: Boolean) =
        if (isWeekend) DayCellBase.WEEKEND else DayCellBase.SURFACE

    private fun custodyOverlay(custody: String?): DayCellOverlay? = when (custody) {
        "mom" -> DayCellOverlay.CUSTODY_MOM
        "dad" -> DayCellOverlay.CUSTODY_DAD
        else -> null
    }
}
