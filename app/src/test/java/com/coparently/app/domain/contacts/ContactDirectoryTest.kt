package com.coparently.app.domain.contacts

import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The contacts list is a screen someone opens in a hurry, so the two things that must not be
 * wrong are which number a tap dials and whether there is one to dial at all.
 */
class ContactDirectoryTest {

    @Test
    fun `the list is the children's own emergency contacts, one group each`() {
        val groups = ContactDirectory.of(
            listOf(
                child("c1", "Mia", contact("Nina", "Grandmother", "+420111")),
                child("c2", "Tom", contact("Dr Novak", "Paediatrician", "+420222"))
            )
        )

        assertEquals(listOf("Mia", "Tom"), groups.map { it.childName })
        assertEquals(listOf("c1", "c2"), groups.map { it.childId })
        assertEquals(listOf("Nina"), groups[0].contacts.map { it.name })
        assertEquals(listOf("Dr Novak"), groups[1].contacts.map { it.name })
    }

    @Test
    fun `a child with no contacts is left out rather than shown empty`() {
        val groups = ContactDirectory.of(
            listOf(
                child("c1", "Mia"),
                child("c2", "Tom", contact("Dr Novak", "Paediatrician", "+420222"))
            )
        )

        assertEquals(listOf("Tom"), groups.map { it.childName })
    }

    @Test
    fun `an entry with no phone is not dialable`() {
        val contact = ContactDirectory
            .of(listOf(child("c1", "Mia", contact("Nina", "Grandmother", phone = ""))))
            .single().contacts.single()

        assertEquals(emptyList(), contact.numbers)
        assertNull(contact.dialable)
    }

    @Test
    fun `whitespace is not a phone number`() {
        val contact = ContactDirectory
            .of(listOf(child("c1", "Mia", contact("Nina", "Grandmother", phone = "   "))))
            .single().contacts.single()

        assertNull(contact.dialable)
    }

    @Test
    fun `a tap dials the first recorded number`() {
        val contact = ContactDirectory
            .of(
                listOf(
                    child(
                        "c1", "Mia",
                        contact("Nina", "Grandmother", phone = "+420111", alternate = "+420999")
                    )
                )
            )
            .single().contacts.single()

        assertEquals(listOf("+420111", "+420999"), contact.numbers)
        assertEquals("+420111", contact.dialable)
    }

    @Test
    fun `a record carrying only an alternate number still dials`() {
        // The distinction between the two fields is which one was typed first, and a parent
        // looking for a number in a hurry does not care.
        val contact = ContactDirectory
            .of(
                listOf(
                    child("c1", "Mia", contact("Nina", "Grandmother", phone = "", alternate = "+420999"))
                )
            )
            .single().contacts.single()

        assertEquals("+420999", contact.dialable)
    }

    @Test
    fun `two people with the same name under one child get different keys`() {
        val contacts = ContactDirectory
            .of(
                listOf(
                    child(
                        "c1", "Mia",
                        contact("Nina", "Grandmother", "+420111"),
                        contact("Nina", "Neighbour", "+420222")
                    )
                )
            )
            .single().contacts

        assertEquals(2, contacts.map { it.key }.toSet().size)
    }

    private fun contact(name: String, relationship: String, phone: String, alternate: String? = null) =
        EmergencyContact(name = name, relationship = relationship, phone = phone, alternatePhone = alternate)

    private fun child(id: String, name: String, vararg contacts: EmergencyContact) = ChildInfo(
        id = id,
        childName = name,
        dateOfBirth = null,
        emergencyContacts = contacts.toList(),
        createdAt = LocalDateTime.of(2026, 8, 1, 9, 0),
        updatedAt = LocalDateTime.of(2026, 8, 1, 9, 0)
    )
}
