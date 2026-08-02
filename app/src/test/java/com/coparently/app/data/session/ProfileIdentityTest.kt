package com.coparently.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ProfileIdentity.resolveName], [ProfileIdentity.resolveEmail] and
 * [ProfileIdentity.resolvePhotoUrl] — specifically the `providerData` fallback rung.
 *
 * The bug this closes: a genuine, non-anonymous Google session whose account record
 * predates linking the provider has `displayName`/`email` empty at the top level, even
 * though `providerData` carries a `google.com` entry with all three fields. Before this
 * fallback existed, every one of these tests but [nothing usable anywhere resolves to null]
 * would have failed to compile (the parameter did not exist) and, with the parameter added
 * but ignored, would fail on the assertion — that is the falsification for each.
 */
class ProfileIdentityTest {

    @Test
    fun `a provider name is used when the top-level field is absent`() {
        val name = ProfileIdentity.resolveName(
            displayName = null,
            storedRemoteName = null,
            storedLocalName = null,
            email = null,
            providers = listOf(googleProvider(displayName = "Alice Novak"))
        )

        // Falsifies against a resolveName that never looks at providers at all: it would
        // return null here (or "alice" from the email guess, if one were supplied) instead.
        assertEquals("Alice Novak", name)
    }

    @Test
    fun `a stored name is not overridden by a provider value`() {
        val name = ProfileIdentity.resolveName(
            displayName = null,
            storedRemoteName = "Alice Novak",
            storedLocalName = null,
            email = null,
            providers = listOf(googleProvider(displayName = "Alice N. (Google)"))
        )

        // Falsifies against a fallback inserted at the front or the very end of the chain
        // instead of between the stored rungs and the email guess.
        assertEquals("Alice Novak", name)
    }

    @Test
    fun `a provider name beats the email local part guess`() {
        val name = ProfileIdentity.resolveName(
            displayName = null,
            storedRemoteName = null,
            storedLocalName = null,
            email = "alice@example.com",
            providers = listOf(googleProvider(displayName = "Alice Novak"))
        )

        // Falsifies against a fallback placed after the email guess: it would return
        // "alice" instead.
        assertEquals("Alice Novak", name)
    }

    @Test
    fun `the synthetic firebase entry is ignored as a name source`() {
        val name = ProfileIdentity.resolveName(
            displayName = null,
            storedRemoteName = null,
            storedLocalName = null,
            email = null,
            providers = listOf(
                ProfileIdentity.ProviderIdentity(
                    providerId = "firebase",
                    displayName = "Should never surface",
                    email = "synthetic@example.com",
                    photoUrl = "https://example.com/synthetic.png"
                )
            )
        )

        // Falsifies against a bestProvider() that does not filter the synthetic entry out.
        assertNull(name)
    }

    @Test
    fun `google is preferred over another linked provider`() {
        val name = ProfileIdentity.resolveName(
            displayName = null,
            storedRemoteName = null,
            storedLocalName = null,
            email = null,
            providers = listOf(
                ProfileIdentity.ProviderIdentity(
                    providerId = "password",
                    displayName = "Should lose to google.com",
                    email = null,
                    photoUrl = null
                ),
                googleProvider(displayName = "Alice Novak")
            )
        )

        assertEquals("Alice Novak", name)
    }

    @Test
    fun `nothing usable anywhere resolves to null`() {
        val name = ProfileIdentity.resolveName(
            displayName = null,
            storedRemoteName = null,
            storedLocalName = null,
            email = null,
            providers = emptyList()
        )

        assertNull(name)
    }

    @Test
    fun `a provider email is used when the top-level and stored fields are absent`() {
        val email = ProfileIdentity.resolveEmail(
            topLevelEmail = null,
            storedRemoteEmail = null,
            storedLocalEmail = null,
            providers = listOf(googleProvider(email = "alice@example.com"))
        )

        assertEquals("alice@example.com", email)
    }

    @Test
    fun `a stored email is not overridden by a provider value`() {
        val email = ProfileIdentity.resolveEmail(
            topLevelEmail = null,
            storedRemoteEmail = null,
            storedLocalEmail = "alice.stored@example.com",
            providers = listOf(googleProvider(email = "alice.provider@example.com"))
        )

        assertEquals("alice.stored@example.com", email)
    }

    @Test
    fun `the synthetic firebase entry is ignored as an email source`() {
        val email = ProfileIdentity.resolveEmail(
            topLevelEmail = null,
            storedRemoteEmail = null,
            storedLocalEmail = null,
            providers = listOf(
                ProfileIdentity.ProviderIdentity(
                    providerId = "firebase",
                    displayName = null,
                    email = "synthetic@example.com",
                    photoUrl = null
                )
            )
        )

        assertNull(email)
    }

    @Test
    fun `a provider photo is used when the top-level and stored fields are absent`() {
        val photoUrl = ProfileIdentity.resolvePhotoUrl(
            authPhotoUrl = null,
            storedRemoteUrl = null,
            storedLocalUrl = null,
            providers = listOf(googleProvider(photoUrl = "https://lh3.googleusercontent.com/a/alice"))
        )

        assertEquals("https://lh3.googleusercontent.com/a/alice", photoUrl)
    }

    @Test
    fun `a stored photo is not overridden by a provider value`() {
        val photoUrl = ProfileIdentity.resolvePhotoUrl(
            authPhotoUrl = null,
            storedRemoteUrl = "https://lh3.googleusercontent.com/a/stored",
            storedLocalUrl = null,
            providers = listOf(googleProvider(photoUrl = "https://lh3.googleusercontent.com/a/provider"))
        )

        assertEquals("https://lh3.googleusercontent.com/a/stored", photoUrl)
    }

    @Test
    fun `the synthetic firebase entry is ignored as a photo source`() {
        val photoUrl = ProfileIdentity.resolvePhotoUrl(
            authPhotoUrl = null,
            storedRemoteUrl = null,
            storedLocalUrl = null,
            providers = listOf(
                ProfileIdentity.ProviderIdentity(
                    providerId = "firebase",
                    displayName = null,
                    email = null,
                    photoUrl = "https://example.com/synthetic.png"
                )
            )
        )

        assertNull(photoUrl)
    }

    private fun googleProvider(
        displayName: String? = null,
        email: String? = null,
        photoUrl: String? = null
    ) = ProfileIdentity.ProviderIdentity(
        providerId = "google.com",
        displayName = displayName,
        email = email,
        photoUrl = photoUrl
    )
}
