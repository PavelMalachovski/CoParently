package com.coparently.app.data.sync

/**
 * The medical photographs on a child-info document, read from the wire.
 *
 * A pure helper for the same reason [ChildInfoAudience] is one: the child document is built and
 * read in **seven** places — `ChildInfoRepositoryImpl`'s four mappers and `SyncService`'s upload
 * map, its conflict map and its reader — and a field that behaves slightly differently in two of
 * them is a defect nothing catches. Package B1 shipped exactly that shape of bug, twice.
 *
 * This does not solve the underlying problem, which is that there are seven maps at all. It does
 * mean the *reading* of this field is written once and tested once.
 */
object ChildInfoPhotos {

    /**
     * The download URLs on a document, in the order they were added.
     *
     * Never throws and never returns null. Three cases arrive here and all three are ordinary:
     *
     * - **The key is absent.** Every document written before this field existed. An empty list is
     *   the true answer, not merely a safe one — those children have no photographs.
     * - **The value is not a list of strings.** A hand-edited document, or a newer build writing
     *   a shape this one does not know. Dropping it beats throwing on the path that renders a
     *   child's medical record.
     * - **An entry is blank.** A failed upload that stored an empty URL would otherwise become a
     *   thumbnail that can never load and can only be removed by guessing which one it is.
     *
     * @param value The document's `medicalPhotos` field, however Firestore deserialised it.
     */
    fun decode(value: Any?): List<String> = (value as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}
