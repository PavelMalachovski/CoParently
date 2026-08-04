package com.coparently.app.presentation.expenses

/** Outcome of a horizontal drag over the Expenses month header. */
enum class MonthStep { PREVIOUS, NEXT, NONE }

/**
 * Turns an accumulated horizontal drag into a month step.
 *
 * The gesture lives on the month header only — the expense rows below already own horizontal
 * drags for swipe-to-delete, and two horizontal gestures in one list is how you get a row that
 * sometimes deletes and sometimes pages.
 */
object MonthSwipe {

    /**
     * @param dragPx Total horizontal travel since the gesture began; negative is right-to-left.
     * @param thresholdPx Travel required to commit, in pixels at the current density.
     */
    fun resolve(dragPx: Float, thresholdPx: Float): MonthStep = when {
        dragPx <= -thresholdPx -> MonthStep.NEXT
        dragPx >= thresholdPx -> MonthStep.PREVIOUS
        else -> MonthStep.NONE
    }
}
