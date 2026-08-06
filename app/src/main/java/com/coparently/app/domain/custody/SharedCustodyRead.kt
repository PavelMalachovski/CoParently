package com.coparently.app.domain.custody

/**
 * What a one-shot read of the pair's shared custody document found.
 *
 * Three answers, not two, because the difference between them decides whether a caller may act.
 * A single nullable [SharedCustody] conflated "the pair has no document" with "this device could
 * not look" — and a caller that reads the second as the first concludes the co-parent has no
 * schedule and writes its own over theirs. That is the one outcome the pairing conflict screen
 * exists to prevent, arrived at by never showing the screen.
 */
sealed interface SharedCustodyRead {

    /** The document exists and was read. */
    data class Found(val custody: SharedCustody) : SharedCustodyRead

    /**
     * The read succeeded and there is no document: this pair has never shared a pattern.
     * The only answer that entitles a caller to publish its own without asking anyone.
     */
    data object Absent : SharedCustodyRead

    /**
     * The question could not be answered — the read was denied or unreachable, or this device
     * does not yet know who it is paired with.
     *
     * That last case is not exotic; it is the common one immediately after pairing.
     * `CustodyModelRepository` derives the document id from Room's `partnerId`, which is written
     * asynchronously by the pairing listener rather than by the accept callable, so a read made
     * seconds after an accept routinely has no pair to look up yet. Callers must defer, never
     * treat this as [Absent].
     */
    data object Unavailable : SharedCustodyRead
}
