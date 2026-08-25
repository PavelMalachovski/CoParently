package com.coparently.app.domain.family

/**
 * Who a record is about: one of the family's children, or one of its pets.
 *
 * One definition of the vocabulary, the same shape as `data/sync/Tombstone.kt` and
 * `data/remote/firebase/PushPayload.kt` — typed in Kotlin, a plain string on the wire.
 *
 * **Why one type rather than a `childId` and a `petId`.** A vet's bill is an expense, and with a
 * child-only field there was nowhere to put it — `Expense.childId` could not name the animal the
 * money was spent on. A visit to the vet and a visit to the dentist are the same shape of thing,
 * so they get the same reference, one picker and one filter. The prefix convention is not
 * invented here either: `ContactDirectory` already grouped pets into a child-shaped id by hand.
 *
 * **An empty list means the whole family, not "unattributed".** That is what every record
 * written before this type existed holds, and reading it as "everyone" is what stops an upgrade
 * from hiding a month of expenses behind a filter nobody has set. It is the reading `FamilyKind`
 * gives an unanswered account, for the same reason.
 *
 * **A reference this build does not understand survives a round trip.** [Unknown] keeps the
 * stored text verbatim so that reading a record written by a newer build — one that learned to
 * reference a grandparent, say — and saving it back does not silently strip the reference.
 * Dropping the unknown on read is the more obvious thing to do and it is a data-loss bug: the
 * older build would not merely fail to show the tag, it would erase it on the next edit.
 * [Unknown] deliberately matches no filter and renders as nothing.
 *
 * The wire form is a JSON array of these strings — never a Gson serialisation of this type.
 * R8 rewrote the field names of a Gson-mapped model once already and it shipped; a list of
 * plain strings has no field names to rewrite.
 */
sealed interface FamilyMemberRef {

    /** How this reference is written to Room and to Firestore. */
    val stored: String

    /** One of the family's children, by `ChildInfo.id`. */
    data class Child(val id: String) : FamilyMemberRef {
        override val stored: String get() = CHILD_PREFIX + id
    }

    /** One of the family's pets, by `Pet.id`. */
    data class Pet(val id: String) : FamilyMemberRef {
        override val stored: String get() = PET_PREFIX + id
    }

    /**
     * A reference written by a build that knows a kind this one does not.
     *
     * Carried through unchanged so an edit here cannot erase it. It is nobody's child and
     * nobody's pet, so [childIds] and [petIds] pass it by and no chip ever selects it.
     */
    data class Unknown(override val stored: String) : FamilyMemberRef

    companion object {
        /** Prefix for a child reference. Part of the stored schema — never renamed. */
        const val CHILD_PREFIX = "child:"

        /** Prefix for a pet reference. Part of the stored schema — never renamed. */
        const val PET_PREFIX = "pet:"

        /**
         * Reads one stored reference.
         *
         * @return the reference, or null for text that names nobody — blank, or a bare prefix
         *   with no id behind it. An unrecognised *prefix* is not nothing: it becomes [Unknown].
         */
        fun of(stored: String): FamilyMemberRef? = when {
            stored.isBlank() -> null
            stored.startsWith(CHILD_PREFIX) ->
                stored.removePrefix(CHILD_PREFIX).takeIf { it.isNotBlank() }?.let(::Child)
            stored.startsWith(PET_PREFIX) ->
                stored.removePrefix(PET_PREFIX).takeIf { it.isNotBlank() }?.let(::Pet)
            else -> Unknown(stored)
        }

        /**
         * Reads a stored list, from Room's JSON column or from a Firestore array.
         *
         * Takes `Any?` because a Firestore document hands back an untyped `List<*>` whose
         * contents are whatever was written — a number, a map, a null — and a record that
         * cannot be read must not take the screen down with it. Anything that is not a usable
         * string is dropped, and duplicates collapse: naming the same child twice means the
         * same thing as naming them once.
         */
        fun parse(raw: Any?): List<FamilyMemberRef> = when (raw) {
            is List<*> -> raw.filterIsInstance<String>().mapNotNull { of(it) }.distinct()
            else -> emptyList()
        }

        /** Writes a list back out, in the order given. */
        fun store(refs: List<FamilyMemberRef>): List<String> = refs.map { it.stored }

        /**
         * Reads the single `childId` that expenses and budgets carried before this type.
         *
         * Every row production ever wrote holds null there — nothing set it, and the two DAO
         * queries that read it had no callers — so in practice this converts nothing. It exists
         * because a co-parent on an older build still writes the field, and because a
         * conversion that costs three lines is cheaper than finding out we were wrong about
         * "nothing set it".
         */
        fun fromLegacyChildId(childId: String?): List<FamilyMemberRef> =
            childId?.takeIf { it.isNotBlank() }?.let { listOf(Child(it)) }.orEmpty()
    }
}

/** The children named here, in order. [FamilyMemberRef.Unknown]s are not children. */
fun List<FamilyMemberRef>.childIds(): List<String> =
    filterIsInstance<FamilyMemberRef.Child>().map { it.id }

/** The pets named here, in order. */
fun List<FamilyMemberRef>.petIds(): List<String> =
    filterIsInstance<FamilyMemberRef.Pet>().map { it.id }

/**
 * Whether this record **names** [member].
 *
 * Deliberately not "is this record about [member]", which for an unset list would be true of
 * everybody — the two questions are one keystroke apart and produce opposite screens. A filter
 * asks this one: chipping "Anya" must show what was attributed to Anya, not the whole untagged
 * pile as well, or every chip shows the same list and the filter tells the parent nothing. An
 * untagged record belongs under "everyone", which is where an empty list puts it by not
 * matching any chip.
 */
fun List<FamilyMemberRef>.names(member: FamilyMemberRef): Boolean = contains(member)
