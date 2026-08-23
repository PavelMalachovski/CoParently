package com.coparently.app.domain.contacts

import com.coparently.app.domain.model.ChildInfo

/**
 * One person in the contacts list.
 *
 * @property name Who they are.
 * @property relationship How they relate to the child — free text, and deliberately so: it
 *   already carries "grandmother" or "paediatrician" perfectly well, which is why item 16's
 *   wider cast needed no second model.
 * @property numbers Every number recorded for them, in the order they were entered, with blanks
 *   dropped. Empty when the record has no number at all.
 * @property key A stable list key. Two children can legitimately list the same grandmother, and
 *   one child can list two people with the same name, so the key carries the child, the position
 *   and the name rather than any one of them.
 */
data class DirectoryContact(
    val name: String,
    val relationship: String,
    val numbers: List<String>,
    val key: String
) {
    /**
     * The number a tap should dial, or null when there is none.
     *
     * The first recorded number wins. A record with only an alternate number still dials — the
     * distinction between "phone" and "alternatePhone" is about which one was typed first, and
     * a parent looking for a number in a hurry does not care.
     */
    val dialable: String? get() = numbers.firstOrNull()
}

/**
 * A child and the people worth phoning about them.
 *
 * @property childId The child's id, so the list key survives two children sharing a name.
 * @property childName The child.
 * @property contacts Their contacts, in the order the child's record lists them.
 */
data class ContactGroup(
    val childId: String,
    val childName: String,
    val contacts: List<DirectoryContact>
)

/**
 * The contacts list, built from the children's own records.
 *
 * **One list, widened in meaning, rather than a second model.** `ChildInfo.emergencyContacts` is
 * already shared with the co-parent and editable by both, and a doctor is an emergency contact by
 * any reasonable reading. Two parallel lists would mean a parent has to guess which one grandma
 * went into, and then guess again when they need her.
 *
 * Pure, so the flattening, the blank-number rule and the keys can be tested without a repository.
 */
object ContactDirectory {

    /**
     * The groups to render, one per child that has at least one contact.
     *
     * A child with no contacts is omitted rather than shown empty: this is a screen someone
     * opens with a hurt child in the other arm, and a header with nothing under it is one more
     * thing to read past.
     *
     * @param children Every child on record.
     */
    fun of(children: List<ChildInfo>): List<ContactGroup> = children
        .map { child ->
            ContactGroup(
                childId = child.id,
                childName = child.childName,
                contacts = child.emergencyContacts.mapIndexed { index, contact ->
                    DirectoryContact(
                        name = contact.name,
                        relationship = contact.relationship,
                        numbers = listOfNotNull(contact.phone, contact.alternatePhone)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                        key = "${child.id}#$index#${contact.name}"
                    )
                }
            )
        }
        .filter { it.contacts.isNotEmpty() }
}
