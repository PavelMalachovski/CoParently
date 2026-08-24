package com.coparently.app.domain.expenses

/** A whole share, in basis points. 10 000 bp = 100 %. */
const val FULL_SHARE_BASIS_POINTS = 10_000

/** Basis points in one percent, for turning a picker's whole number into a stored share. */
private const val BASIS_POINTS_PER_PERCENT = 100

/**
 * A whole share expressed in percent — the scale the split picker works in.
 *
 * Basis points are the storage unit precisely because a percent cannot express a third,
 * but the control a parent drags is in percent and clamps against this.
 */
const val WHOLE_PERCENT = FULL_SHARE_BASIS_POINTS / BASIS_POINTS_PER_PERCENT

/**
 * How the two parents have agreed to divide a shared cost.
 *
 * **Basis points, not a `Double`.** Money is the one thing in this app both parents check
 * against each other, and a ratio stored as `0.6666666666666666` re-multiplied through a month
 * of expenses drifts by cents that neither of them can account for. An integer share divides
 * exactly and compares exactly, on both phones and across the wire.
 *
 * **A slot share, never a name or a uid.** [momShareBasisPoints] is slot 1's share, the same
 * `"mom"`/`"dad"` schema identifiers `Expense.paidBy` is resolved against and that the app never
 * shows — `ParentLabels` turns a slot into the person. A ratio keyed on a uid would break at
 * re-pairing; a ratio keyed on a name would break at a rename.
 *
 * @property momShareBasisPoints Slot 1's share, `0..`[FULL_SHARE_BASIS_POINTS]. Slot 2 takes the
 *   remainder, so the two always sum to a whole and cannot disagree.
 */
@JvmInline
value class SplitRatio(val momShareBasisPoints: Int) {

    init {
        require(momShareBasisPoints in 0..FULL_SHARE_BASIS_POINTS) {
            "A share is 0..$FULL_SHARE_BASIS_POINTS basis points, not $momShareBasisPoints"
        }
    }

    /** Slot 2's share. Derived, so the pair can never be stored inconsistently. */
    val dadShareBasisPoints: Int get() = FULL_SHARE_BASIS_POINTS - momShareBasisPoints

    /** Slot 1's share as a fraction, for multiplying an amount. */
    val momShare: Double get() = momShareBasisPoints.toDouble() / FULL_SHARE_BASIS_POINTS

    /** Slot 2's share as a fraction. */
    val dadShare: Double get() = 1.0 - momShare

    /** Slot 1's share as a whole percent, for the picker and the label. */
    val momPercent: Int get() = momShareBasisPoints / BASIS_POINTS_PER_PERCENT

    /** Slot 2's share as a whole percent. */
    val dadPercent: Int get() = WHOLE_PERCENT - momPercent

    /**
     * The fraction [slot] pays of a shared cost.
     *
     * @param slot `"mom"` or `"dad"`; anything else is not a party to the agreement and pays
     *   nothing — a stranger's uid must never be turned into a debt.
     */
    fun shareFor(slot: String?): Double = when (slot) {
        "mom" -> momShare
        "dad" -> dadShare
        else -> 0.0
    }

    companion object {
        /** Half each, which is what a family has until they agree otherwise. */
        val EVEN = SplitRatio(FULL_SHARE_BASIS_POINTS / 2)

        /**
         * A ratio from a whole percent for slot 1.
         *
         * @param momPercent `0..100`; anything outside is clamped rather than refused, because
         *   the only producer is a picker that cannot leave the range and a stored value that
         *   somehow did would otherwise crash the screen reading it.
         */
        fun ofMomPercent(momPercent: Int): SplitRatio =
            SplitRatio(momPercent.coerceIn(0, WHOLE_PERCENT) * BASIS_POINTS_PER_PERCENT)

        /**
         * A stored value read back, or null when it cannot describe a share.
         *
         * Null rather than [EVEN] for an unreadable value: "we never agreed" and "we agreed on
         * half each" are different facts, and only the caller knows which one to fall back to.
         */
        fun fromStored(basisPoints: Int?): SplitRatio? =
            basisPoints?.takeIf { it in 0..FULL_SHARE_BASIS_POINTS }?.let { SplitRatio(it) }
    }
}
