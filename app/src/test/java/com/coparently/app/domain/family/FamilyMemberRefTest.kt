package com.coparently.app.domain.family

import com.coparently.app.domain.family.FamilyMemberRef.Child
import com.coparently.app.domain.family.FamilyMemberRef.Pet
import com.coparently.app.domain.family.FamilyMemberRef.Unknown
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored vocabulary for "who this record is about".
 *
 * Most of these pin things that are cheap to break and expensive to notice: the two prefixes are
 * schema, an unreadable Firestore array must not take a screen down, and an empty list means the
 * whole family rather than nobody.
 */
class FamilyMemberRefTest {

    @Test
    fun `the two prefixes are the stored schema`() {
        // A co-parent's phone parses what this one writes. Renaming either prefix silently
        // unlinks every record already in the pair's Firestore.
        assertEquals("child:", FamilyMemberRef.CHILD_PREFIX)
        assertEquals("pet:", FamilyMemberRef.PET_PREFIX)
    }

    @Test
    fun `a child and a pet survive a round trip`() {
        val refs = listOf(Child("c1"), Pet("p1"))
        assertEquals(refs, FamilyMemberRef.parse(FamilyMemberRef.store(refs)))
    }

    @Test
    fun `an id containing a colon is not cut short`() {
        // Nothing generates one today - the ids are UUIDs - but splitting on the last colon
        // instead of the prefix would corrupt one silently rather than failing.
        val ref = Child("a:b:c")
        assertEquals(ref, FamilyMemberRef.of(ref.stored))
    }

    @Test
    fun `a reference this build does not understand survives a round trip`() {
        // The whole point of Unknown. Dropping it on read would mean an older build erases a
        // newer build's tag on the next edit, which is data loss rather than a missing feature.
        val stored = listOf("grandparent:g1", "child:c1")
        val parsed = FamilyMemberRef.parse(stored)

        assertEquals(listOf(Unknown("grandparent:g1"), Child("c1")), parsed)
        assertEquals(stored, FamilyMemberRef.store(parsed))
    }

    @Test
    fun `an unknown reference matches nobody`() {
        val refs = listOf<FamilyMemberRef>(Unknown("grandparent:g1"))
        assertFalse(refs.names(Child("g1")), "an unknown reference is nobody's child")
        assertFalse(refs.names(Pet("g1")), "and nobody's pet")
        assertFalse(refs.names(Unknown("grandparent:other")), "and not some other unknown")
    }

    @Test
    fun `text that names nobody reads as nothing`() {
        assertNull(FamilyMemberRef.of(""))
        assertNull(FamilyMemberRef.of("   "))
        assertNull(FamilyMemberRef.of("child:"), "a bare prefix names no child")
        assertNull(FamilyMemberRef.of("pet:"), "a bare prefix names no pet")
    }

    @Test
    fun `a Firestore array of anything at all is survivable`() {
        // Firestore hands back whatever was written. A record that cannot be read must not take
        // the screen down with it.
        val raw = listOf("child:c1", 42, null, mapOf("a" to "b"), "", "pet:p1")
        assertEquals(listOf(Child("c1"), Pet("p1")), FamilyMemberRef.parse(raw))

        assertEquals(emptyList(), FamilyMemberRef.parse(null))
        assertEquals(emptyList(), FamilyMemberRef.parse("child:c1"), "a bare string is not a list")
    }

    @Test
    fun `naming the same child twice means naming them once`() {
        assertEquals(
            listOf(Child("c1"), Pet("p1")),
            FamilyMemberRef.parse(listOf("child:c1", "pet:p1", "child:c1"))
        )
    }

    @Test
    fun `an unset record names nobody, so it matches no chip`() {
        // Every record written before this type holds an empty list. If "unset" matched every
        // member, every chip would show the same untagged pile and the filter would say nothing.
        val unset = emptyList<FamilyMemberRef>()
        assertFalse(unset.names(Child("c1")))
        assertFalse(unset.names(Pet("p1")))
    }

    @Test
    fun `a list names who it names and nobody else`() {
        val anya = listOf<FamilyMemberRef>(Child("c1"))
        assertTrue(anya.names(Child("c1")))
        assertFalse(anya.names(Child("c2")))
        assertFalse(anya.names(Pet("c1")), "a pet and a child with the same id are not the same")
    }

    @Test
    fun `the legacy childId converts, and a blank one converts to nothing`() {
        assertEquals(listOf(Child("c1")), FamilyMemberRef.fromLegacyChildId("c1"))
        assertEquals(emptyList(), FamilyMemberRef.fromLegacyChildId(null))
        assertEquals(emptyList(), FamilyMemberRef.fromLegacyChildId(""))
    }

    @Test
    fun `an unknown reference is still selectable by its own stored text`() {
        // Not a feature anybody uses - it is the proof that Unknown is a value like any other,
        // so carrying one through an edit cannot corrupt the rest of the list.
        val refs = listOf<FamilyMemberRef>(Unknown("grandparent:g1"), Child("c1"))
        assertTrue(refs.names(Unknown("grandparent:g1")))
    }
}
