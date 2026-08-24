package com.coparently.app.data.repository

import com.coparently.app.domain.friends.FriendProfile
import com.coparently.app.domain.friends.FriendRole
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding the two friend documents.
 *
 * This is the path that decides whether a third person sees a family's calendar, and every field
 * on it arrives as `Any?` from a document another build may have written — so the cases here are
 * the malformed ones, not the happy path.
 */
class FriendMappersTest {

    private val goodGrant = mapOf<String, Any?>(
        "familyParents" to listOf("mom", "dad"),
        "name" to "Babushka",
        "grantedBy" to "mom",
        "grantedAtMillis" to 1L,
        "expiresAtMillis" to 99L
    )

    @Test
    fun `a well-formed grant decodes`() {
        val grant = FriendMappers.grantFrom("friend", goodGrant)
        assertEquals("friend", grant?.friendUid)
        assertEquals(listOf("mom", "dad"), grant?.familyParents)
        assertEquals(99L, grant?.expiresAtMillis)
    }

    @Test
    fun `a grant naming other than two parents is dropped`() {
        // The events query is a `whereIn` over this list: one uid under-fetches, three reach
        // past the family. Neither is a grant.
        assertNull(FriendMappers.grantFrom("friend", goodGrant + ("familyParents" to listOf("mom"))))
        assertNull(
            FriendMappers.grantFrom(
                "friend",
                goodGrant + ("familyParents" to listOf("mom", "dad", "x"))
            )
        )
        assertNull(FriendMappers.grantFrom("friend", goodGrant - "familyParents"))
    }

    @Test
    fun `a grant with no expiry is dropped rather than treated as forever`() {
        assertNull(FriendMappers.grantFrom("friend", goodGrant - "expiresAtMillis"))
        assertNull(FriendMappers.grantFrom("friend", goodGrant + ("expiresAtMillis" to 0L)))
    }

    @Test
    fun `a grant whose parents are the wrong type is dropped`() {
        assertNull(FriendMappers.grantFrom("friend", goodGrant + ("familyParents" to "mom,dad")))
        assertNull(
            FriendMappers.grantFrom("friend", goodGrant + ("familyParents" to listOf(1, 2)))
        )
    }

    @Test
    fun `a nameless profile is dropped, because access nobody can name cannot be revoked`() {
        assertNull(FriendMappers.profileFrom("friend", mapOf("role" to "GUARDIAN")))
        assertNull(FriendMappers.profileFrom("friend", mapOf("name" to "  ")))
    }

    @Test
    fun `an unknown role falls back to FRIEND rather than throwing`() {
        // A build that adds a fourth role must not crash this one on the screen that draws it.
        val profile = FriendMappers.profileFrom("f", mapOf("name" to "N", "role" to "NEIGHBOUR"))
        assertEquals(FriendRole.FRIEND, profile?.role)
    }

    @Test
    fun `blank phones and blood groups are dropped on the way in and out`() {
        val decoded = FriendMappers.profileFrom(
            "f",
            mapOf("name" to "N", "phones" to listOf("+420", "", "  "), "bloodGroup" to "")
        )
        assertEquals(listOf("+420"), decoded?.phones)
        assertNull(decoded?.bloodGroup)

        val encoded = FriendMappers.profileToMap(
            FriendProfile(uid = "f", name = "N", phones = listOf("+420", ""), bloodGroup = " ")
        )
        assertEquals(listOf("+420"), encoded["phones"])
        assertTrue("bloodGroup" !in encoded)
    }

    @Test
    fun `a profile round-trips through the map`() {
        val profile = FriendProfile(
            uid = "f",
            name = "Babushka",
            role = FriendRole.GRANDPARENT,
            phones = listOf("+420111"),
            bloodGroup = "A+",
            photoUrl = "https://x/y.jpg",
            familyParents = listOf("mom", "dad")
        )
        assertEquals(profile, FriendMappers.profileFrom("f", FriendMappers.profileToMap(profile)))
    }
}
