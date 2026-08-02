package com.coparently.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for [ProfileIdentity.describeNameSources].
 *
 * This string exists to answer one question from a logcat line alone: when
 * [ProfileIdentity.resolveName] returns null, *which* of its inputs were empty and what
 * kind of session is this? The original warning said only "no name could be derived",
 * which is equally consistent with a Google account missing its profile scope, an
 * anonymous session, and a Firestore read that failed — three completely different fixes.
 *
 * The second property pinned here is the one that is easy to regress: the line must carry
 * presence flags and provider constants and nothing else. Logcat is readable by anything
 * with adb access to the device, and an email address in a log line is a leak that was
 * already removed once on this branch.
 */
class ProfileIdentityDiagnosticTest {

    @Test
    fun `reports every absent input and the provider ids`() {
        val line = ProfileIdentity.describeNameSources(
            hasDisplayName = false,
            hasRemoteName = false,
            hasLocalName = false,
            hasEmail = false,
            hasRemoteProfile = true,
            isAnonymous = false,
            providerIds = listOf("google.com")
        )

        assertEquals(
            "displayName=absent remoteName=absent localName=absent email=absent " +
                "remoteProfile=present anonymous=false providers=[google.com]",
            line
        )
    }

    @Test
    fun `distinguishes a session with no provider at all`() {
        // The signal that separates "Google sign-in that reported nothing" from
        // "this is not a Google session in the first place".
        val line = ProfileIdentity.describeNameSources(
            hasDisplayName = false,
            hasRemoteName = false,
            hasLocalName = false,
            hasEmail = false,
            hasRemoteProfile = false,
            isAnonymous = true,
            providerIds = emptyList()
        )

        assertEquals(
            "displayName=absent remoteName=absent localName=absent email=absent " +
                "remoteProfile=absent anonymous=true providers=[none]",
            line
        )
    }

    @Test
    fun `never carries the values themselves`() {
        val line = ProfileIdentity.describeNameSources(
            hasDisplayName = true,
            hasRemoteName = true,
            hasLocalName = true,
            hasEmail = true,
            hasRemoteProfile = true,
            isAnonymous = false,
            providerIds = listOf("password", "google.com")
        )

        // There is no parameter that could carry one, and that is the point: the call site
        // can only pass booleans, so no future edit can accidentally interpolate a name.
        assertFalse(line.contains("@"))
        assertEquals(
            "displayName=present remoteName=present localName=present email=present " +
                "remoteProfile=present anonymous=false providers=[password,google.com]",
            line
        )
    }
}
