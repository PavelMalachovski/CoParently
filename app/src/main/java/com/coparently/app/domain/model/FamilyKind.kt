package com.coparently.app.domain.model

/**
 * Who a family is co-parenting: children, pets, or both.
 *
 * A *set*, not a single choice, because a separated family with a child and a dog has both and
 * would otherwise have to pretend one of them does not exist. An empty set means the question
 * has not been answered — every account that predates it — and reads as "show everything", so an
 * upgrade never hides a section somebody was already using.
 *
 * Stored as the constant names joined by [SEPARATOR], never as a localized label: the value goes
 * into Room and into the co-parent's Firestore read, and both must survive a language change.
 * [fromStored] drops a name this build does not know rather than throwing, the same
 * forward-compatibility rule `PetSpecies.fromStored` follows.
 */
enum class FamilyKind {
    /** The family has children, so the child records and their contacts are offered. */
    CHILDREN,

    /** The family has pets, so the pet records are offered. */
    PETS;

    companion object {
        /** Joins the stored names. A pipe, matching `PreferenceKeys.LIST_SEPARATOR`. */
        const val SEPARATOR = "|"

        /** Every kind, which is what an unanswered account is shown. */
        val ALL: Set<FamilyKind> = entries.toSet()

        /**
         * Reads a stored value back.
         *
         * @param stored The joined constant names, or null/blank for an unanswered account.
         * @return The kinds, or an empty set when nothing readable is stored.
         */
        fun fromStored(stored: String?): Set<FamilyKind> {
            if (stored.isNullOrBlank()) return emptySet()
            return stored.split(SEPARATOR)
                .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
                .toSet()
        }

        /**
         * Writes a set out.
         *
         * @return The joined names, or null for an empty set — null and `""` must not be two
         *   spellings of the same thing in a nullable column that a whole-row comparison reads.
         */
        fun toStored(kinds: Set<FamilyKind>): String? =
            kinds.takeIf { it.isNotEmpty() }
                ?.sortedBy { it.ordinal }
                ?.joinToString(SEPARATOR) { it.name }

        /**
         * What the app should actually show for a pair.
         *
         * The **union** of the two parents' answers, and an unanswered parent contributes
         * nothing rather than everything: one parent saying "children" while the other has never
         * been asked must not hide the child records. An empty union — neither parent has
         * answered — reads as [ALL], so nothing is ever hidden by silence.
         */
        fun effective(mine: Set<FamilyKind>, theirs: Set<FamilyKind>): Set<FamilyKind> =
            (mine + theirs).takeIf { it.isNotEmpty() } ?: ALL
    }
}
