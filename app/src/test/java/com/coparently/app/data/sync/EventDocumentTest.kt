package com.coparently.app.data.sync

import com.coparently.app.domain.family.FamilyMemberRef
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The events wire format, at the one field that crosses between two builds in both directions.
 *
 * `EventDocument` is the single reader of an `events` document and, since the member reference
 * landed, the single converter of the Room JSON column into the document's array. Both halves
 * are pinned here because the failure they prevent is silent: a tag that vanishes on the other
 * parent's phone looks exactly like a tag nobody set.
 */
class EventDocumentTest {

    @Test
    fun `a document's members round-trip through the Room column`() {
        val json = EventDocument.membersJson(listOf("child:c1", "pet:p1"))
        assertEquals(listOf("child:c1", "pet:p1"), EventDocument.storedMembers(json))
    }

    @Test
    fun `an event about nobody is the whole family, in both directions`() {
        // Every event created before the reference type existed. An absent key must not become
        // anything other than "no members", or an upgrade starts hiding events behind a filter.
        assertEquals("[]", EventDocument.membersJson(null))
        assertEquals(emptyList(), EventDocument.storedMembers("[]"))
    }

    @Test
    fun `a reference this build does not understand is carried, not dropped`() {
        // A co-parent on a newer build tags an event with something this one has no name for.
        // Dropping it here would mean an edit made on this phone erases their tag.
        val json = EventDocument.membersJson(listOf("grandparent:g1", "child:c1"))
        assertEquals(listOf("grandparent:g1", "child:c1"), EventDocument.storedMembers(json))
    }

    @Test
    fun `the stored form does not depend on which build wrote the document`() {
        // Normalised on the way in: blanks dropped, duplicates collapsed, non-strings ignored.
        val json = EventDocument.membersJson(listOf("child:c1", "", "child:c1", 7, null))
        assertEquals(listOf("child:c1"), EventDocument.storedMembers(json))
    }

    @Test
    fun `a corrupt column reads as no members rather than throwing`() {
        // The column is NOT NULL with a '[]' default, so this should be unreachable — but it is
        // read on the path that draws the grid, and a throw there takes the calendar down.
        assertEquals(emptyList(), EventDocument.storedMembers("not json"))
    }

    @Test
    fun `the document keeps the prefixes the reference type defines`() {
        val json = EventDocument.membersJson(
            FamilyMemberRef.store(listOf(FamilyMemberRef.Child("c1"), FamilyMemberRef.Pet("p1")))
        )
        assertEquals(listOf("child:c1", "pet:p1"), EventDocument.storedMembers(json))
    }
}
